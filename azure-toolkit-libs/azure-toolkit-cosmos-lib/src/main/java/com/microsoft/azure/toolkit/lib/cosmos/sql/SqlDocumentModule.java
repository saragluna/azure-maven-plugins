/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.cosmos.sql;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.resourcemanager.resources.fluentcore.arm.ResourceId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzResourceModule;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.cosmos.ICosmosDocumentModule;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class SqlDocumentModule extends AbstractAzResourceModule<SqlDocument, SqlContainer, ObjectNode> implements ICosmosDocumentModule<SqlDocument> {

    public static final String DELIMITER = "#";
    private Iterator<FeedResponse<ObjectNode>> iterator;

    public SqlDocumentModule(@Nonnull SqlContainer parent) {
        super("documents", parent);
    }

    public void loadMoreDocuments() {
        if (hasMoreDocuments()) {
            final FeedResponse<ObjectNode> response = iterator.next();
            response.getElements().stream()
                    .map(this::newResource)
                    .forEach(document -> addResourceToLocal(document.getId(), document));
        }
    }

    public boolean hasMoreDocuments() {
        return iterator.hasNext();
    }

    @Nonnull
    @Override
    protected Stream<ObjectNode> loadResourcesFromAzure() {
        final CosmosContainer client = getClient();
        if (client == null) {
            return Stream.empty();
        }
        final int cosmosBatchSize = Azure.az().config().getCosmosBatchSize();
        this.iterator = client.queryItems("select * from c", new CosmosQueryRequestOptions(), ObjectNode.class)
                .iterableByPage(cosmosBatchSize).iterator();
        return iterator.hasNext() ? iterator.next().getElements().stream() : Stream.empty();
    }

    @Nullable
    @Override
    protected ObjectNode loadResourceFromAzure(@Nonnull String name, @Nullable String resourceGroup) {
        final String[] split = name.split(DELIMITER);
        if (split.length > 2) {
            return null;
        }
        final String id = split[0];
        final String partitionKey = split.length > 1 ? split[1] : StringUtils.EMPTY;
        return Optional.ofNullable(getClient())
                .map(client -> Optional.ofNullable(doLoadDocument(client, new PartitionKey(partitionKey), id))
                        .orElseGet(() -> doLoadDocument(client, PartitionKey.NONE, id)))
                .orElse(null);
    }

    @Nullable
    private ObjectNode doLoadDocument(@Nonnull CosmosContainer container, @Nonnull PartitionKey partitionKey, @Nonnull String id) {
        try {
            return container.readItem(id, partitionKey, ObjectNode.class).getItem();
        } catch (RuntimeException e2) {
            return null;
        }
    }

    @Nullable
    public SqlDocument get(@Nonnull String id, @Nonnull String partitionKey, @Nullable String resourceGroup) {
        return super.get(String.format("%s#%s", id, partitionKey), resourceGroup);
    }

    @Nonnull
    @Override
    protected SqlDocument newResource(@Nonnull ObjectNode ObjectNode) {
        final SqlContainer container = getParent();
        final String id = Objects.requireNonNull(ObjectNode.get("id")).asText();
        final String partitionKey = container.getPartitionKey();
        final String partitionValue = Optional.ofNullable(ObjectNode.get(partitionKey))
                .map(JsonNode::asText).orElse(StringUtils.EMPTY);
        return newResource(String.format("%s#%s", id, partitionValue), container.getResourceGroupName());
    }

    @Nonnull
    @Override
    protected SqlDocument newResource(@Nonnull String name, @Nullable String resourceGroup) {
        return new SqlDocument(name, resourceGroup, this);
    }

    @Override
    protected void deleteResourceFromAzure(@Nonnull String resourceId) {
        final ResourceId id = ResourceId.fromString(resourceId);
        final ObjectNode node = loadResourceFromAzure(id.name(), id.resourceGroupName());
        Optional.ofNullable(getClient()).ifPresent(client -> client.deleteItem(node, new CosmosItemRequestOptions()));
    }

    @Nonnull
    @Override
    protected AzResource.Draft<SqlDocument, ObjectNode> newDraftForCreate(@Nonnull String name, @Nullable String rgName) {
        return new SqlDocumentDraft(name, rgName, this);
    }

    @Nonnull
    @Override
    protected AzResource.Draft<SqlDocument, ObjectNode> newDraftForUpdate(@Nonnull SqlDocument document) {
        return new SqlDocumentDraft(document);
    }

    @Override
    @Nullable
    protected synchronized CosmosContainer getClient() {
        return getParent().getClient();
    }
}
