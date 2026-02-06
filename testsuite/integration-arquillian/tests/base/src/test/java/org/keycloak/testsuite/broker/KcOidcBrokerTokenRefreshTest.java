/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.broker;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.Test;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.testsuite.util.AdminClientUtil;
import org.keycloak.testsuite.util.oauth.OAuthClient;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.keycloak.testsuite.broker.BrokerTestConstants.IDP_OIDC_ALIAS;

/**
 * Integration test to verify that token refresh properly persists updated tokens to the database.
 * 
 * This test verifies the fix for a bug where refreshed tokens from external Identity Providers
 * were not being persisted to the database. The bug manifested when Keycloak successfully
 * refreshed expired tokens from an external IDP but failed to call updateFederatedIdentity()
 * to persist the new token data.
 * 
 * Test Flow:
 * 1. Configure IDP with token storage enabled
 * 2. Perform initial authentication to create federated identity with tokens
 * 3. Directly manipulate database to expire the stored token
 * 4. Trigger token refresh via the broker token endpoint
 * 5. Verify that refreshed token data is persisted in the database
 * 
 * Critical Implementation Details:
 * - Uses EntityManager.clear() to bypass Hibernate cache and verify actual database state
 * - Explicitly loads realm in server-side contexts to avoid NullPointerException
 * - Directly manipulates token expiration in database to force refresh scenario
 */
public class KcOidcBrokerTokenRefreshTest extends AbstractInitializedBaseBrokerTest {

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return KcOidcBrokerConfiguration.INSTANCE;
    }

    /**
     * Test that verifies token refresh properly updates the database.
     * 
     * This test ensures that when a token is refreshed from an external IDP,
     * the new token data (including access token, refresh token, and expiration time)
     * is correctly persisted to the database via updateFederatedIdentity().
     * 
     * Without the fix, this test would fail because the refreshed tokens would only
     * exist in memory but not be persisted to the database.
     */
    @Test
    public void testTokenRefreshUpdatesDatabase() throws Exception {
        // Phase 1: Setup and Configuration
        // Enable token storage in the identity provider configuration
        RealmResource consumerRealm = realmsResouce().realm(bc.consumerRealmName());
        IdentityProviderResource identityProviderResource = consumerRealm.identityProviders().get(bc.getIDPAlias());
        IdentityProviderRepresentation representation = identityProviderResource.toRepresentation();
        representation.setStoreToken(true);
        identityProviderResource.update(representation);

        // Phase 2: Initial Authentication
        // Perform broker login to create the initial federated identity with tokens
        logInAsUserInIDPForFirstTimeAndAssertSuccess();

        // Setup permissions and credentials for the federated user to access the token endpoint
        testingClient.server(bc.consumerRealmName()).run(session -> 
            setupUserForApiAccess(session, bc.consumerRealmName(), bc.getUserLogin(), bc.getUserPassword())
        );

        // Retrieve the initial token from database for comparison
        TokenData initialTokenData = getFederatedIdentityTokenData(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());
        assertThat("Initial token should not be null", initialTokenData.tokenJson, notNullValue());
        assertThat("Initial token should have expires_in > 0", initialTokenData.expiresIn, greaterThan(0L));

        // Phase 3: Database Manipulation (Simulate Token Expiration)
        // Directly modify the database to set the token as expired
        // This forces the token refresh mechanism to be triggered
        testingClient.server(bc.consumerRealmName()).run(session -> 
            expireTokenInDatabase(session, bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias())
        );

        // Verify that the token is actually expired in the database
        TokenData expiredTokenData = getFederatedIdentityTokenData(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());
        assertThat("Token should be expired (expires_in = 0)", expiredTokenData.expiresIn, equalTo(0L));

        // Phase 4: Trigger Token Refresh
        // Call the broker token endpoint which should detect the expired token and refresh it
        // Authenticate as the federated user to get an access token for the API call
        oauth.realm(bc.consumerRealmName());
        oauth.clientId("broker-app");
        oauth.clientSecret("broker-app-secret");
        org.keycloak.testsuite.util.oauth.AccessTokenResponse tokenResponse = 
            oauth.doPasswordGrantRequest(bc.getUserLogin(), bc.getUserPassword());
        assertThat("Should receive access token for API call", tokenResponse.getAccessToken(), notNullValue());

        // Call the broker token endpoint: GET /realms/{realm}/broker/{idp-alias}/token
        // This endpoint retrieves the current token and refreshes it if expired
        try (Client client = AdminClientUtil.createResteasyClient()) {
            Response response = client.target(OAuthClient.AUTH_SERVER_ROOT)
                .path("/realms/" + bc.consumerRealmName() + "/broker/" + bc.getIDPAlias() + "/token")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenResponse.getAccessToken())
                .get();

            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new RuntimeException("Token retrieval failed with status " + response.getStatus() + ": " + body);
            }
            assertThat("Token endpoint should return 200 OK", response.getStatus(), equalTo(200));
        }

        // Phase 5: Verification
        // Retrieve the token from database again and verify it has been refreshed and persisted
        TokenData refreshedTokenData = getFederatedIdentityTokenData(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());
        
        // Critical assertions to verify the fix
        assertThat("Refreshed token JSON should be different from initial token", 
            refreshedTokenData.tokenJson, not(equalTo(initialTokenData.tokenJson)));
        assertThat("Refreshed token should have expires_in > 0 (not expired)", 
            refreshedTokenData.expiresIn, greaterThan(0L));
        assertThat("Refreshed token expires_in should be different from expired value", 
            refreshedTokenData.expiresIn, not(equalTo(0L)));
        
        // Additional verification: access token should have changed
        if (initialTokenData.accessToken != null && refreshedTokenData.accessToken != null) {
            assertThat("Access token should have changed after refresh", 
                refreshedTokenData.accessToken, not(equalTo(initialTokenData.accessToken)));
        }
    }

    /**
     * Retrieves token data from the database for a federated identity.
     * 
     * CRITICAL: This method uses EntityManager.clear() to bypass Hibernate's cache
     * and ensure we're reading the actual persisted data from the database.
     * Without this, we might get cached data that doesn't reflect the actual database state.
     * 
     * @param realmName The name of the consumer realm
     * @param username The username of the federated user
     * @param idpAlias The alias of the identity provider
     * @return TokenData containing the token JSON and parsed expiration information
     */
    private TokenData getFederatedIdentityTokenData(String realmName, String username, String idpAlias) {
        return testingClient.server(realmName).fetch(session -> {
            // REQUIREMENT 1: Always explicitly load the realm in server-side contexts
            // session.getContext().getRealm() often returns null in testingClient.server() blocks
            RealmModel realm = session.realms().getRealmByName(realmName);
            if (realm == null) {
                throw new RuntimeException("Realm not found: " + realmName);
            }

            // Retrieve the user
            UserModel user = session.users().getUserByUsername(realm, username);
            if (user == null) {
                throw new RuntimeException("User not found: " + username);
            }

            // REQUIREMENT 2: Clear EntityManager cache before reading from database
            // This is CRITICAL to ensure we're reading actual persisted data, not cached data
            session.getProvider(JpaConnectionProvider.class).getEntityManager().clear();

            // Retrieve the federated identity AFTER clearing the cache
            FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
            if (identity == null) {
                throw new RuntimeException("Federated Identity not found for user: " + username + ", idp: " + idpAlias);
            }

            // Parse the token JSON to extract expiration information
            String tokenJson = identity.getToken();
            TokenData data = new TokenData();
            data.tokenJson = tokenJson;
            
            try {
                if (tokenJson != null && tokenJson.startsWith("{")) {
                    JsonNode tokenNode = JsonSerialization.readValue(tokenJson, JsonNode.class);
                    
                    // Extract expires_in field
                    if (tokenNode.has("expires_in")) {
                        data.expiresIn = tokenNode.get("expires_in").asLong();
                    }
                    
                    // Extract access token for comparison
                    if (tokenNode.has("access_token")) {
                        data.accessToken = tokenNode.get("access_token").asText();
                    }
                    
                    // Extract access token expiration if available
                    if (tokenNode.has("accessTokenExpiration")) {
                        data.accessTokenExpiration = tokenNode.get("accessTokenExpiration").asLong();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse token JSON", e);
            }
            
            return data;
        }, TokenData.class);
    }

    /**
     * Expires a token in the database by setting expires_in to 0.
     * 
     * This simulates the scenario where a stored token has expired and needs to be refreshed.
     * The method directly manipulates the federated identity in the database.
     * 
     * @param session The Keycloak session
     * @param realmName The name of the consumer realm
     * @param username The username of the federated user
     * @param idpAlias The alias of the identity provider
     */
    private static void expireTokenInDatabase(KeycloakSession session, String realmName, String username, String idpAlias) {
        // REQUIREMENT 1: Always explicitly load the realm
        RealmModel realm = session.realms().getRealmByName(realmName);
        if (realm == null) {
            throw new RuntimeException("Realm not found: " + realmName);
        }

        // Retrieve the user
        UserModel user = session.users().getUserByUsername(realm, username);
        if (user == null) {
            throw new RuntimeException("User not found: " + username);
        }

        // Retrieve the federated identity
        FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
        if (identity == null) {
            throw new RuntimeException("Federated Identity not found for user: " + username + ", idp: " + idpAlias);
        }

        try {
            // Parse the current token JSON
            String tokenJson = identity.getToken();
            if (tokenJson != null && tokenJson.startsWith("{")) {
                JsonNode tokenNode = JsonSerialization.readValue(tokenJson, JsonNode.class);
                
                // Create a modified version with expires_in set to 0
                // This simulates an expired token
                com.fasterxml.jackson.databind.node.ObjectNode modifiedToken = 
                    (com.fasterxml.jackson.databind.node.ObjectNode) tokenNode;
                modifiedToken.put("expires_in", 0);
                
                // Also set accessTokenExpiration to a past time if it exists
                if (modifiedToken.has("accessTokenExpiration")) {
                    modifiedToken.put("accessTokenExpiration", 0);
                }
                
                // Create updated identity with expired token
                String expiredTokenJson = JsonSerialization.writeValueAsString(modifiedToken);
                FederatedIdentityModel updatedIdentity = new FederatedIdentityModel(
                    identity.getIdentityProvider(),
                    identity.getUserId(),
                    identity.getUserName(),
                    expiredTokenJson
                );
                
                // Persist the updated identity to the database
                session.users().updateFederatedIdentity(realm, user, updatedIdentity);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to expire token in database", e);
        }
    }

    /**
     * Sets up a user with the necessary permissions and credentials to access the broker token endpoint.
     * 
     * The broker token endpoint requires:
     * - The user to be authenticated
     * - The user to have the "read-token" role from the "broker" client
     * 
     * @param session The Keycloak session
     * @param realmName The name of the consumer realm
     * @param username The username of the user to setup
     * @param password The password to set for the user
     */
    private static void setupUserForApiAccess(KeycloakSession session, String realmName, String username, String password) {
        // REQUIREMENT 1: Always explicitly load the realm
        RealmModel realm = session.realms().getRealmByName(realmName);
        if (realm == null) {
            throw new RuntimeException("Realm not found: " + realmName);
        }

        // Find the broker client which manages the token endpoint
        ClientModel brokerClient = realm.getClientByClientId("broker");
        if (brokerClient == null) {
            throw new RuntimeException("Client 'broker' not found in realm: " + realmName);
        }

        // Get the read-token role required to access the token endpoint
        RoleModel readTokenRole = brokerClient.getRole("read-token");
        if (readTokenRole == null) {
            throw new RuntimeException("Role 'read-token' not found in broker client");
        }

        // Find the user
        UserModel user = session.users().getUserByUsername(realm, username);
        if (user == null) {
            throw new RuntimeException("User '" + username + "' not found in realm: " + realmName);
        }

        // Grant the read-token role to the user
        user.grantRole(readTokenRole);

        // Set the user's password for authentication
        user.credentialManager().updateCredential(UserCredentialModel.password(password));
    }

    /**
     * Data class to hold parsed token information.
     * This is used to transfer token data from server-side code to test code.
     */
    public static class TokenData {
        public String tokenJson;
        public Long expiresIn;
        public String accessToken;
        public Long accessTokenExpiration;
    }
}
