/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.core.azurecli;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.AzureEnvironment;
import com.azure.identity.implementation.util.ScopeUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.util.AzureCliUtils;
import com.microsoft.azure.toolkit.lib.common.utils.JsonUtils;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

class AzureCliTokenCredentialManager extends TokenCredentialManager {
    public AzureCliTokenCredentialManager(AzureEnvironment env) {
        this.environment = env;
        rootCredentialSupplier = () -> new AzureCliTokenCredential(null);
        credentialSupplier = AzureCliTokenCredential::new;
    }

    @AllArgsConstructor
    static class AzureCliTokenCredential implements TokenCredential {
        private static final String CLI_GET_ACCESS_TOKEN_CMD = "az account get-access-token --resource %s %s --output json";
        private static final String CLOUD_SHELL_ENV_KEY = "ACC_CLOUD";
        private final String tenantId;

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            final String scopes = ScopeUtil.scopesToResource(request.getScopes());

            try {
                ScopeUtil.validateScope(scopes);
            } catch (IllegalArgumentException ex) {
                throw new AzureToolkitAuthenticationException(String.format("Invalid scope: %s", scopes));
            }

            final String azCommand = String.format(CLI_GET_ACCESS_TOKEN_CMD, scopes,
                    (StringUtils.isBlank(tenantId) || isInCloudShell()) ? "" : (" -t " + tenantId));
            JsonObject result = JsonUtils.getGson().fromJson(AzureCliUtils.executeAzureCli(azCommand), JsonObject.class);

            // copied from https://github.com/Azure/azure-sdk-for-java/blob/master/sdk/identity/azure-identity
            // /src/main/java/com/azure/identity/implementation/IdentityClient.java#L487
            String accessToken = result.get("accessToken").getAsString();
            final OffsetDateTime expiresDateTime = Optional.ofNullable(result.get("expiresOn"))
                    .filter(jsonElement -> !jsonElement.isJsonNull())
                    .map(JsonElement::getAsString)
                    .map(value -> value.substring(0, value.indexOf(".")))
                    .map(value -> String.join("T", value.split(" "))).map(value -> LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            .atZone(ZoneId.systemDefault()).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC))
                    .orElse(OffsetDateTime.MAX);
            return Mono.just(new AccessToken(accessToken, expiresDateTime));
        }

        boolean isInCloudShell() {
            return System.getenv(CLOUD_SHELL_ENV_KEY) != null;
        }
    }
}
