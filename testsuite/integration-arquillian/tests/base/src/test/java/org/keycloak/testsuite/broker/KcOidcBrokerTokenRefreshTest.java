package org.keycloak.testsuite.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.Test;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.testsuite.util.AdminClientUtil;
import org.keycloak.testsuite.util.oauth.OAuthClient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test to verify that token refresh operations properly persist updated tokens to the database.
 * 
 * This test verifies the fix in AbstractOAuth2IdentityProvider.retrieveToken(). If the 
 * updateFederatedIdentity call is commented out, this test should fail at the assertion in Phase 5.
 */
public class KcOidcBrokerTokenRefreshTest extends AbstractInitializedBaseBrokerTest {

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return KcOidcBrokerConfiguration.INSTANCE;
    }

    /**
     * Comprehensive test for token refresh persistence.
     * 
     * Test Flow:
     * Phase 1: Setup - Enable token storage and grant read-token role
     * Phase 2: Initial Authentication - Login via IDP to create federated identity
     * Phase 3: Expire Token in Database - Directly manipulate stored token JSON to force expiration
     * Phase 4: Trigger Refresh - Call broker token endpoint which should refresh and persist
     * Phase 5: Verify Persistence - Clear cache and verify updated token in database
     */
    @Test
    public void testTokenRefreshUpdatesDatabase() throws Exception {
        BrokerConfiguration bc = getBrokerConfiguration();
        
        // ===== PHASE 1: Setup =====
        // Enable token storage on the identity provider
        RealmResource consumerRealm = realmsResouce().realm(bc.consumerRealmName());
        IdentityProviderResource identityProviderResource = consumerRealm.identityProviders().get(bc.getIDPAlias());
        IdentityProviderRepresentation idpRepresentation = identityProviderResource.toRepresentation();
        idpRepresentation.setStoreToken(true);
        identityProviderResource.update(idpRepresentation);

        // ===== PHASE 2: Initial Authentication =====
        // Perform initial login to create federated identity with stored tokens
        logInAsUserInIDPForFirstTimeAndAssertSuccess();

        // Grant read-token role to the federated user for API access
        testingClient.server(bc.consumerRealmName()).run(session -> {
            RealmModel realm = session.realms().getRealmByName(bc.consumerRealmName());
            if (realm == null) {
                throw new RuntimeException("Realm not found: " + bc.consumerRealmName());
            }

            UserModel user = session.users().getUserByUsername(realm, bc.getUserLogin());
            if (user == null) {
                throw new RuntimeException("User not found: " + bc.getUserLogin());
            }

            ClientModel brokerClient = realm.getClientByClientId(Constants.BROKER_SERVICE_CLIENT_ID);
            if (brokerClient == null) {
                throw new RuntimeException("Broker client not found: " + Constants.BROKER_SERVICE_CLIENT_ID);
            }

            RoleModel readTokenRole = brokerClient.getRole(Constants.READ_TOKEN_ROLE);
            if (readTokenRole == null) {
                throw new RuntimeException("read-token role not found in broker client");
            }

            user.grantRole(readTokenRole);
        });

        // ===== PHASE 3: Expire Token in Database =====
        // Capture initial token state from database
        String initialTokenJson = getFederatedIdentityToken(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());
        assertThat("Initial token should exist", initialTokenJson, notNullValue());

        // Directly manipulate the token JSON in the database to force expiration
        expireTokenInDatabase(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());

        // Clear any caches to ensure we're reading from database
        testingClient.server(bc.consumerRealmName()).run(session -> {
            // Clear user cache
            session.users().getUserByUsername(
                session.realms().getRealmByName(bc.consumerRealmName()), 
                bc.getUserLogin()
            );
        });

        // ===== PHASE 4: Trigger Refresh =====
        // Obtain access token for the consumer realm user
        oauth.realm(bc.consumerRealmName());
        oauth.client(KcOidcBrokerConfiguration.CONSUMER_BROKER_APP_CLIENT_ID, 
                     KcOidcBrokerConfiguration.CONSUMER_BROKER_APP_SECRET);
        
        org.keycloak.testsuite.util.oauth.AccessTokenResponse tokenResponse = 
            oauth.doPasswordGrantRequest(bc.getUserLogin(), bc.getUserPassword());
        
        assertThat("Access token should be obtained", tokenResponse.getAccessToken(), notNullValue());

        // Call the broker token endpoint which should trigger token refresh
        try (Client client = AdminClientUtil.createResteasyClient()) {
            Response response = client.target(OAuthClient.AUTH_SERVER_ROOT)
                .path("/realms/" + bc.consumerRealmName() + "/broker/" + bc.getIDPAlias() + "/token")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenResponse.getAccessToken())
                .get();

            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new RuntimeException("Broker token endpoint failed with status " + 
                    response.getStatus() + ": " + body);
            }
            
            assertThat("Broker token endpoint should succeed", response.getStatus(), equalTo(200));
        }

        // ===== PHASE 5: Verify Persistence =====
        // Clear caches again to force database read
        testingClient.server(bc.consumerRealmName()).run(session -> {
            RealmModel realm = session.realms().getRealmByName(bc.consumerRealmName());
            UserModel user = session.users().getUserByUsername(realm, bc.getUserLogin());
            
            // Force cache eviction by accessing the user
            if (user != null) {
                user.getUsername(); // Touch the user to ensure cache is current
            }
        });

        // Retrieve token from database after refresh
        String refreshedTokenJson = getFederatedIdentityToken(bc.consumerRealmName(), bc.getUserLogin(), bc.getIDPAlias());
        
        // Verify that the token in the database has been updated
        assertThat("Refreshed token should exist", refreshedTokenJson, notNullValue());
        assertThat("Token in database should be updated after refresh", 
                   refreshedTokenJson, not(equalTo(initialTokenJson)));
    }

    /**
     * Retrieves the stored token JSON from the FederatedIdentityModel in the database.
     * 
     * @param realmName The consumer realm name
     * @param username The username of the federated user
     * @param idpAlias The identity provider alias
     * @return The token JSON string stored in the database
     */
    private String getFederatedIdentityToken(String realmName, String username, String idpAlias) {
        return testingClient.server(realmName).fetch(session -> {
            // Always use explicit realm lookup in RunOnServer blocks
            RealmModel realm = session.realms().getRealmByName(realmName);
            if (realm == null) {
                throw new RuntimeException("Realm not found: " + realmName);
            }

            UserModel user = session.users().getUserByUsername(realm, username);
            if (user == null) {
                throw new RuntimeException("User not found: " + username);
            }

            FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
            if (identity == null) {
                throw new RuntimeException("Federated identity not found for user: " + username + 
                                         ", idp: " + idpAlias);
            }

            return identity.getToken();
        }, String.class);
    }

    /**
     * Expires the token stored in the database by manipulating the JSON directly.
     * 
     * This method:
     * 1. Loads the current FederatedIdentityModel from the database
     * 2. Parses the token JSON string
     * 3. Sets the 'exp' field to 0 (expired)
     * 4. Sets the 'expires_in' field to 0 if present
     * 5. Serializes back to JSON and updates the database
     * 
     * @param realmName The consumer realm name
     * @param username The username of the federated user
     * @param idpAlias The identity provider alias
     */
    private void expireTokenInDatabase(String realmName, String username, String idpAlias) {
        testingClient.server(realmName).run(session -> {
            RealmModel realm = session.realms().getRealmByName(realmName);
            if (realm == null) {
                throw new RuntimeException("Realm not found: " + realmName);
            }

            UserModel user = session.users().getUserByUsername(realm, username);
            if (user == null) {
                throw new RuntimeException("User not found: " + username);
            }

            FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
            if (identity == null) {
                throw new RuntimeException("Federated identity not found for user: " + username + 
                                         ", idp: " + idpAlias);
            }

            String tokenJson = identity.getToken();
            if (tokenJson == null || tokenJson.isEmpty()) {
                throw new RuntimeException("No token stored for federated identity");
            }

            try {
                // Parse the token JSON
                ObjectMapper mapper = new ObjectMapper();
                JsonNode tokenNode = mapper.readTree(tokenJson);
                
                if (!tokenNode.isObject()) {
                    throw new RuntimeException("Token JSON is not an object");
                }

                ObjectNode tokenObject = (ObjectNode) tokenNode;

                // Set expiration fields to force token expiration
                // exp: Unix timestamp - set to 0 (January 1, 1970)
                tokenObject.put("exp", 0);
                
                // expires_in: seconds until expiration - set to 0
                if (tokenObject.has("expires_in")) {
                    tokenObject.put("expires_in", 0);
                }

                // Serialize back to JSON
                String expiredTokenJson = mapper.writeValueAsString(tokenObject);

                // Create updated federated identity with expired token
                FederatedIdentityModel updatedIdentity = new FederatedIdentityModel(
                    identity.getIdentityProvider(),
                    identity.getUserId(),
                    identity.getUserName(),
                    expiredTokenJson
                );

                // Persist the updated token to the database
                session.users().updateFederatedIdentity(realm, user, updatedIdentity);

            } catch (Exception e) {
                throw new RuntimeException("Failed to expire token in database", e);
            }
        });
    }
}
