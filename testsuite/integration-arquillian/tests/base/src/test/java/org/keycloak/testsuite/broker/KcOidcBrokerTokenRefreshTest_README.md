# KcOidcBrokerTokenRefreshTest - Comprehensive Documentation

## Overview

This integration test verifies a critical bug fix in Keycloak's token refresh mechanism for external Identity Provider (IDP) integrations. The test ensures that when tokens are refreshed from an external IDP, the updated tokens are properly persisted to the database.

## The Bug Being Tested

### Problem Description

When Keycloak integrates with external Identity Providers (like another Keycloak instance, Google, GitHub, etc.), it can store the tokens received from these providers. When these tokens expire, Keycloak has a mechanism to refresh them automatically.

**The Bug:** The token refresh mechanism in `AbstractOAuth2IdentityProvider.retrieveToken()` successfully obtained new tokens from the external IDP, but failed to persist these refreshed tokens to the database. This meant:

1. The refreshed tokens existed only in memory during the request
2. Subsequent requests would still see the old, expired tokens from the database
3. After a server restart, the refreshed tokens would be lost entirely

### The Fix

The fix (already implemented in lines 268-272 of `AbstractOAuth2IdentityProvider.java`) ensures that after a successful token refresh, the updated tokens are persisted:

```java
if (getConfig().isStoreToken()) {
    RealmModel realm = session.getContext().getRealm();
    UserModel user = session.users().getUserById(realm, identity.getUserId());
    session.users().updateFederatedIdentity(realm, user, identity);
}
```

## Test Architecture

### Test Flow

The test follows a 5-phase approach:

#### Phase 1: Setup and Configuration
- Enables token storage in the Identity Provider configuration
- This is required for tokens to be stored in the database at all

#### Phase 2: Initial Authentication
- Performs a complete broker login flow
- Creates a federated identity with initial tokens in the database
- Sets up user permissions to access the broker token endpoint

#### Phase 3: Database Manipulation (Token Expiration Simulation)
- Directly modifies the database to set `expires_in` to 0
- This simulates an expired token scenario
- Forces the refresh mechanism to be triggered

#### Phase 4: Token Refresh Trigger
- Calls the broker token endpoint: `GET /realms/{realm}/broker/{idp-alias}/token`
- This endpoint checks if the token is expired and refreshes it if needed
- The refresh should persist the new tokens to the database (the fix being tested)

#### Phase 5: Verification
- Retrieves the token from the database again
- Verifies that the token has been updated (not the expired version)
- Confirms that `expires_in` is now greater than 0

## Critical Implementation Details

### 1. Explicit Realm Loading

**Problem:** In `testingClient.server().run()` blocks, `session.getContext().getRealm()` often returns `null`.

**Solution:** Always explicitly load the realm:
```java
RealmModel realm = session.realms().getRealmByName(realmName);
```

This pattern is used consistently throughout the test in all server-side code blocks.

### 2. Hibernate Cache Bypass

**Problem:** Hibernate maintains a multi-level cache. When you update an entity and then immediately read it back within the same session, you might get the cached version instead of the actual database state.

**Solution:** Clear the EntityManager cache before verification:
```java
session.getProvider(JpaConnectionProvider.class).getEntityManager().clear();
```

This is **CRITICAL** for test reliability. Without this:
- The test might pass even if the fix is not present
- You'd be reading cached data, not actual persisted data
- The test would give false positives

### 3. Direct Database Manipulation

Instead of using time travel (advancing the clock), the test directly manipulates the token in the database to set it as expired. This approach:
- Is more reliable and deterministic
- Doesn't depend on timing or clock manipulation
- Directly tests the specific scenario (expired token in database)

## Test Validation

### How to Verify the Test Works

#### Test Should PASS with the fix:
1. Run the test with the current code
2. The test should complete successfully
3. All assertions should pass

#### Test Should FAIL without the fix:
1. Comment out lines 268-272 in `AbstractOAuth2IdentityProvider.java`:
```java
// if (getConfig().isStoreToken()) {
//     RealmModel realm = session.getContext().getRealm();
//     UserModel user = session.users().getUserById(realm, identity.getUserId());
//     session.users().updateFederatedIdentity(realm, user, identity);
// }
```
2. Run the test again
3. The test should FAIL at the verification phase
4. The assertion `assertThat("Refreshed token should have expires_in > 0", refreshedTokenData.expiresIn, greaterThan(0L))` should fail
5. This proves the test correctly detects the absence of the fix

## Key Classes and Methods

### Classes Under Test
- `AbstractOAuth2IdentityProvider` - The class containing the bug fix
- `OIDCIdentityProvider` - Extends AbstractOAuth2IdentityProvider

### Key Methods
- `AbstractOAuth2IdentityProvider.retrieveToken()` - Contains the fix
- `AbstractOAuth2IdentityProvider.refreshToken()` - Performs the actual token refresh
- `UserProvider.updateFederatedIdentity()` - Persists the updated identity

### Test Infrastructure
- `AbstractInitializedBaseBrokerTest` - Base class providing broker test infrastructure
- `KcOidcBrokerConfiguration` - Configuration for OIDC broker tests
- `testingClient.server().run()` - Executes code server-side in the Keycloak instance

## Token Data Structure

The tokens are stored as JSON in the `FederatedIdentityModel.token` field:

```json
{
  "access_token": "eyJhbGc...",
  "expires_in": 300,
  "refresh_token": "eyJhbGc...",
  "token_type": "Bearer",
  "accessTokenExpiration": 1234567890
}
```

The test specifically verifies:
- `expires_in` - Time in seconds until token expiration
- `access_token` - The actual access token (should change after refresh)
- `accessTokenExpiration` - Unix timestamp of expiration

## Running the Test

### Prerequisites
- Keycloak source code
- Java 17 or later
- Maven

### Execution
```bash
# From the Keycloak root directory
cd testsuite/integration-arquillian/tests/base
mvn test -Dtest=KcOidcBrokerTokenRefreshTest
```

### Expected Output
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## Troubleshooting

### Common Issues

1. **NullPointerException in server-side code**
   - Cause: Using `session.getContext().getRealm()` instead of explicit realm loading
   - Solution: Always use `session.realms().getRealmByName(realmName)`

2. **Test passes even without the fix**
   - Cause: Not clearing the EntityManager cache
   - Solution: Ensure `EntityManager.clear()` is called before verification

3. **Token not being refreshed**
   - Cause: Token storage not enabled or token not actually expired
   - Solution: Verify `setStoreToken(true)` is called and token expiration is set to 0

4. **User doesn't have permission to access token endpoint**
   - Cause: Missing "read-token" role
   - Solution: Ensure `setupUserForApiAccess()` is called and grants the role

## Related Files

- `services/src/main/java/org/keycloak/broker/oidc/AbstractOAuth2IdentityProvider.java` - Contains the fix
- `testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/broker/AbstractInitializedBaseBrokerTest.java` - Base test class
- `testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/broker/KcOidcBrokerConfiguration.java` - Broker configuration

## References

- [Keycloak Broker Documentation](https://www.keycloak.org/docs/latest/server_admin/#_identity_broker)
- [OAuth 2.0 Token Refresh](https://datatracker.ietf.org/doc/html/rfc6749#section-6)
- [Hibernate Caching](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching)
