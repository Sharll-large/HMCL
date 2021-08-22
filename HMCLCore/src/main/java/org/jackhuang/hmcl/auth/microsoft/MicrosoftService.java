/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.auth.*;
import org.jackhuang.hmcl.auth.yggdrasil.RemoteAuthenticationException;
import org.jackhuang.hmcl.auth.yggdrasil.Texture;
import org.jackhuang.hmcl.auth.yggdrasil.TextureType;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.gson.TolerableValidationException;
import org.jackhuang.hmcl.util.gson.Validation;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.ResponseCodeException;
import org.jackhuang.hmcl.util.javafx.ObservableOptionalCache;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.Objects.requireNonNull;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Lang.threadPool;
import static org.jackhuang.hmcl.util.Logging.LOG;
import static org.jackhuang.hmcl.util.Pair.pair;

public class MicrosoftService {
    private static final String CLIENT_ID = "6a3728d6-27a3-4180-99bb-479895b8f88e";
    private static final String AUTHORIZATION_URL = "https://login.live.com/oauth20_authorize.srf";
    private static final String ACCESS_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final int[] PORTS = { 29111, 29112, 29113, 29114, 29115 };
    private static final ThreadPoolExecutor POOL = threadPool("MicrosoftProfileProperties", true, 2, 10,
            TimeUnit.SECONDS);
    private static final Pattern OAUTH_URL_PATTERN = Pattern
            .compile("^https://login\\.live\\.com/oauth20_desktop\\.srf\\?code=(.*?)&lc=(.*?)$");

    private final OAuthCallback callback;

    private final ObservableOptionalCache<String, MinecraftProfileResponse, AuthenticationException> profileRepository;

    public MicrosoftService(OAuthCallback callback) {
        this.callback = requireNonNull(callback);
        this.profileRepository = new ObservableOptionalCache<>(authorization -> {
            LOG.info("Fetching properties");
            return getCompleteProfile(authorization);
        }, (uuid, e) -> LOG.log(Level.WARNING, "Failed to fetch properties of " + uuid, e), POOL);
    }

    public ObservableOptionalCache<String, MinecraftProfileResponse, AuthenticationException> getProfileRepository() {
        return profileRepository;
    }

    public MicrosoftSession authenticate() throws AuthenticationException {
        // Example URL:
        // https://login.live.com/oauth20_authorize.srf?response_type=code&client_id=6a3728d6-27a3-4180-99bb-479895b8f88e&redirect_uri=http://localhost:29111/auth-response&scope=XboxLive.signin+offline_access&state=612fd24a2447427383e8b222b597db66&prompt=select_account
        try {
            // Microsoft OAuth Flow
            OAuthSession session = callback.startServer();
            callback.openBrowser(NetworkUtils.withQuery(AUTHORIZATION_URL,
                    mapOf(pair("client_id", CLIENT_ID), pair("response_type", "code"),
                            pair("redirect_uri", session.getRedirectURI()), pair("scope", SCOPE),
                            pair("prompt", "select_account"))));
            String code = session.waitFor();

            // Authorization Code -> Token
            String responseText = HttpRequest.POST("https://login.live.com/oauth20_token.srf")
                    .form(mapOf(pair("client_id", CLIENT_ID), pair("code", code),
                            pair("grant_type", "authorization_code"), pair("client_secret", session.getClientSecret()),
                            pair("redirect_uri", session.getRedirectURI()), pair("scope", SCOPE)))
                    .getString();
            LiveAuthorizationResponse response = JsonUtils.fromNonNullJson(responseText,
                    LiveAuthorizationResponse.class);

            // Authenticate with XBox Live
            XBoxLiveAuthenticationResponse xboxResponse = HttpRequest
                    .POST("https://user.auth.xboxlive.com/user/authenticate")
                    .json(mapOf(
                            pair("Properties",
                                    mapOf(pair("AuthMethod", "RPS"), pair("SiteName", "user.auth.xboxlive.com"),
                                            pair("RpsTicket", "d=" + response.accessToken))),
                            pair("RelyingParty", "http://auth.xboxlive.com"), pair("TokenType", "JWT")))
                    .accept("application/json").getJson(XBoxLiveAuthenticationResponse.class);
            String uhs = (String) xboxResponse.displayClaims.xui.get(0).get("uhs");

            // Authenticate Minecraft with XSTS
            XBoxLiveAuthenticationResponse minecraftXstsResponse = HttpRequest
                    .POST("https://xsts.auth.xboxlive.com/xsts/authorize")
                    .json(mapOf(
                            pair("Properties",
                                    mapOf(pair("SandboxId", "RETAIL"),
                                            pair("UserTokens", Collections.singletonList(xboxResponse.token)))),
                            pair("RelyingParty", "rp://api.minecraftservices.com/"), pair("TokenType", "JWT")))
                    .getJson(XBoxLiveAuthenticationResponse.class);
            String minecraftXstsUhs = (String) minecraftXstsResponse.displayClaims.xui.get(0).get("uhs");
            if (!Objects.equals(uhs, minecraftXstsUhs)) {
                throw new ServerResponseMalformedException("uhs mismatched");
            }

            // Authenticate XBox with XSTS
            XBoxLiveAuthenticationResponse xboxXstsResponse = HttpRequest
                    .POST("https://xsts.auth.xboxlive.com/xsts/authorize")
                    .json(mapOf(
                            pair("Properties",
                                    mapOf(pair("SandboxId", "RETAIL"),
                                            pair("UserTokens", Collections.singletonList(xboxResponse.token)))),
                            pair("RelyingParty", "http://xboxlive.com"), pair("TokenType", "JWT")))
                    .getJson(XBoxLiveAuthenticationResponse.class);
            String xboxXstsUhs = (String) xboxXstsResponse.displayClaims.xui.get(0).get("uhs");
            if (!Objects.equals(uhs, xboxXstsUhs)) {
                throw new ServerResponseMalformedException("uhs mismatched");
            }

            getXBoxProfile(uhs, xboxXstsResponse.token);

            // Authenticate with Minecraft
            MinecraftLoginWithXBoxResponse minecraftResponse = HttpRequest
                    .POST("https://api.minecraftservices.com/authentication/login_with_xbox")
                    .json(mapOf(pair("identityToken", "XBL3.0 x=" + uhs + ";" + minecraftXstsResponse.token)))
                    .accept("application/json").getJson(MinecraftLoginWithXBoxResponse.class);

            long notAfter = minecraftResponse.expiresIn * 1000L + System.currentTimeMillis();

            // Checking Game Ownership
            MinecraftStoreResponse storeResponse = HttpRequest
                    .GET("https://api.minecraftservices.com/entitlements/mcstore")
                    .authorization(String.format("%s %s", minecraftResponse.tokenType, minecraftResponse.accessToken))
                    .getJson(MinecraftStoreResponse.class);
            handleErrorResponse(storeResponse);
            if (storeResponse.items.isEmpty()) {
                throw new NoCharacterException();
            }

            return new MicrosoftSession(minecraftResponse.tokenType, minecraftResponse.accessToken, notAfter,
                    new MicrosoftSession.User(minecraftResponse.username), null);
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (InterruptedException e) {
            throw new NoSelectedCharacterException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException) {
                throw new NoSelectedCharacterException();
            } else {
                throw new ServerDisconnectException(e);
            }
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    public MicrosoftSession refresh(MicrosoftSession oldSession) throws AuthenticationException {
        try {
            // Get the profile
            MinecraftProfileResponse profileResponse = HttpRequest
                    .GET(NetworkUtils.toURL("https://api.minecraftservices.com/minecraft/profile"))
                    .authorization(String.format("%s %s", oldSession.getTokenType(), oldSession.getAccessToken()))
                    .getJson(MinecraftProfileResponse.class);
            handleErrorResponse(profileResponse);
            return new MicrosoftSession(oldSession.getTokenType(), oldSession.getAccessToken(),
                    oldSession.getNotAfter(), oldSession.getUser(),
                    new MicrosoftSession.GameProfile(profileResponse.id, profileResponse.name));
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    public Optional<MinecraftProfileResponse> getCompleteProfile(String authorization) throws AuthenticationException {
        try {
            return Optional.ofNullable(
                    HttpRequest.GET(NetworkUtils.toURL("https://api.minecraftservices.com/minecraft/profile"))
                            .authorization(authorization).getJson(MinecraftProfileResponse.class));
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    public boolean validate(String tokenType, String accessToken) throws AuthenticationException {
        requireNonNull(tokenType);
        requireNonNull(accessToken);

        try {
            HttpRequest.GET(NetworkUtils.toURL("https://api.minecraftservices.com/minecraft/profile"))
                    .authorization(String.format("%s %s", tokenType, accessToken)).filter((url, responseCode) -> {
                        if (responseCode / 100 == 4) {
                            throw new ResponseCodeException(url, responseCode);
                        }
                    }).getString();
            return true;
        } catch (ResponseCodeException e) {
            return false;
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        }
    }

    private static void handleErrorResponse(MinecraftErrorResponse response) throws AuthenticationException {
        if (response.error != null) {
            throw new RemoteAuthenticationException(response.error, response.errorMessage, response.developerMessage);
        }
    }

    public static Optional<Map<TextureType, Texture>> getTextures(MinecraftProfileResponse profile) {
        Objects.requireNonNull(profile);

        Map<TextureType, Texture> textures = new EnumMap<>(TextureType.class);

        if (!profile.skins.isEmpty()) {
            textures.put(TextureType.SKIN, new Texture(profile.skins.get(0).url, null));
        }
        // if (!profile.capes.isEmpty()) {
        // textures.put(TextureType.CAPE, new Texture(profile.capes.get(0).url, null);
        // }

        return Optional.of(textures);
    }

    private static void getXBoxProfile(String uhs, String xstsToken) throws IOException {
        HttpRequest.GET("https://profile.xboxlive.com/users/me/profile/settings",
                pair("settings", "GameDisplayName,AppDisplayName,AppDisplayPicRaw,GameDisplayPicRaw,"
                        + "PublicGamerpic,ShowUserAsAvatar,Gamerscore,Gamertag,ModernGamertag,ModernGamertagSuffix,"
                        + "UniqueModernGamertag,AccountTier,TenureLevel,XboxOneRep,"
                        + "PreferredColor,Location,Bio,Watermarks," + "RealName,RealNameOverride,IsQuarantined"))
                .contentType("application/json").accept("application/json")
                .authorization(String.format("XBL3.0 x=%s;%s", uhs, xstsToken)).header("x-xbl-contract-version", "3")
                .getString();
    }

    private static MinecraftProfileResponse getMinecraftProfile(String tokenType, String accessToken)
            throws IOException, AuthenticationException {
        HttpURLConnection conn = HttpRequest.GET("https://api.minecraftservices.com/minecraft/profile")
                .contentType("application/json").authorization(String.format("%s %s", tokenType, accessToken))
                .createConnection();
        int responseCode = conn.getResponseCode();
        if (responseCode == HTTP_NOT_FOUND) {
            throw new NoCharacterException();
        }

        String result = NetworkUtils.readData(conn);
        return JsonUtils.fromNonNullJson(result, MinecraftProfileResponse.class);
    }

    /**
     * Error response: {"error":"invalid_grant","error_description":"The provided
     * value for the 'redirect_uri' is not valid. The value must exactly match the
     * redirect URI used to obtain the authorization
     * code.","correlation_id":"??????"}
     */
    private static class LiveAuthorizationResponse {
        @SerializedName("token_type")
        String tokenType;

        @SerializedName("expires_in")
        int expiresIn;

        @SerializedName("scope")
        String scope;

        @SerializedName("access_token")
        String accessToken;

        @SerializedName("refresh_token")
        String refreshToken;

        @SerializedName("user_id")
        String userId;

        @SerializedName("foci")
        String foci;
    }

    private static class XBoxLiveAuthenticationResponseDisplayClaims {
        List<Map<Object, Object>> xui;
    }

    /**
     *
     * Success Response: { "IssueInstant":"2020-12-07T19:52:08.4463796Z",
     * "NotAfter":"2020-12-21T19:52:08.4463796Z", "Token":"token", "DisplayClaims":{
     * "xui":[ { "uhs":"userhash" } ] } }
     *
     * Error response: { "Identity":"0", "XErr":2148916238, "Message":"",
     * "Redirect":"https://start.ui.xboxlive.com/AddChildToFamily" }
     *
     * XErr Candidates: 2148916233 = missing XBox account 2148916238 = child account
     * not linked to a family
     */
    private static class XBoxLiveAuthenticationResponse {
        @SerializedName("IssueInstant")
        String issueInstant;

        @SerializedName("NotAfter")
        String notAfter;

        @SerializedName("Token")
        String token;

        @SerializedName("DisplayClaims")
        XBoxLiveAuthenticationResponseDisplayClaims displayClaims;
    }

    private static class MinecraftLoginWithXBoxResponse {
        @SerializedName("username")
        String username;

        @SerializedName("roles")
        List<String> roles;

        @SerializedName("access_token")
        String accessToken;

        @SerializedName("token_type")
        String tokenType;

        @SerializedName("expires_in")
        int expiresIn;
    }

    private static class MinecraftStoreResponseItem {
        @SerializedName("name")
        String name;
        @SerializedName("signature")
        String signature;
    }

    private static class MinecraftStoreResponse extends MinecraftErrorResponse {
        @SerializedName("items")
        List<MinecraftStoreResponseItem> items;

        @SerializedName("signature")
        String signature;

        @SerializedName("keyId")
        String keyId;
    }

    public static class MinecraftProfileResponseSkin implements Validation {
        public String id;
        public String state;
        public String url;
        public String variant; // CLASSIC, SLIM
        public String alias;

        @Override
        public void validate() throws JsonParseException, TolerableValidationException {
            Validation.requireNonNull(id, "id cannot be null");
            Validation.requireNonNull(state, "state cannot be null");
            Validation.requireNonNull(url, "url cannot be null");
            Validation.requireNonNull(variant, "variant cannot be null");
        }
    }

    public static class MinecraftProfileResponseCape {

    }

    public static class MinecraftProfileResponse extends MinecraftErrorResponse implements Validation {
        @SerializedName("id")
        UUID id;
        @SerializedName("name")
        String name;
        @SerializedName("skins")
        List<MinecraftProfileResponseSkin> skins;
        @SerializedName("capes")
        List<MinecraftProfileResponseCape> capes;

        @Override
        public void validate() throws JsonParseException, TolerableValidationException {
            Validation.requireNonNull(id, "id cannot be null");
            Validation.requireNonNull(name, "name cannot be null");
            Validation.requireNonNull(skins, "skins cannot be null");
            Validation.requireNonNull(capes, "capes cannot be null");
        }
    }

    private static class MinecraftErrorResponse {
        public String path;
        public String errorType;
        public String error;
        public String errorMessage;
        public String developerMessage;
    }

    public interface OAuthCallback {
        /**
         * Start OAuth callback server at localhost.
         *
         * @throws IOException if an I/O error occurred.
         */
        OAuthSession startServer() throws IOException;

        /**
         * Open browser
         *
         * @param url OAuth url.
         */
        void openBrowser(String url) throws IOException;
    }

    public interface OAuthSession {

        String getRedirectURI();

        String getClientSecret() throws IOException;

        /**
         * Wait for authentication
         * 
         * @return authentication code
         * @throws InterruptedException if interrupted
         * @throws ExecutionException   if an I/O error occurred.
         */
        String waitFor() throws InterruptedException, ExecutionException;
    }

}
