/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.catalog;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.stream.Collectors.joining;
import static org.apache.ignite.internal.catalog.CatalogParamsValidationUtils.validateAlterZoneParams;
import static org.apache.ignite.internal.catalog.CatalogParamsValidationUtils.validateCreateZoneParams;
import static org.apache.ignite.internal.catalog.CatalogParamsValidationUtils.validateDropZoneParams;
import static org.apache.ignite.internal.catalog.CatalogParamsValidationUtils.validateRenameZoneParams;
import static org.apache.ignite.internal.catalog.CatalogParamsValidationUtils.validateZoneDataNodesAutoAdjustParametersCompatibility;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.DEFAULT_FILTER;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.INFINITE_TIMER_VALUE;
import static org.apache.ignite.lang.ErrorGroups.Sql.STMT_VALIDATION_ERR;
import static org.apache.ignite.lang.IgniteStringFormatter.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.ignite.internal.catalog.commands.AlterColumnParams;
import org.apache.ignite.internal.catalog.commands.AlterTableAddColumnParams;
import org.apache.ignite.internal.catalog.commands.AlterTableDropColumnParams;
import org.apache.ignite.internal.catalog.commands.AlterZoneParams;
import org.apache.ignite.internal.catalog.commands.CatalogUtils;
import org.apache.ignite.internal.catalog.commands.ColumnParams;
import org.apache.ignite.internal.catalog.commands.CreateHashIndexParams;
import org.apache.ignite.internal.catalog.commands.CreateSortedIndexParams;
import org.apache.ignite.internal.catalog.commands.CreateTableParams;
import org.apache.ignite.internal.catalog.commands.CreateZoneParams;
import org.apache.ignite.internal.catalog.commands.DropIndexParams;
import org.apache.ignite.internal.catalog.commands.DropTableParams;
import org.apache.ignite.internal.catalog.commands.DropZoneParams;
import org.apache.ignite.internal.catalog.commands.RenameZoneParams;
import org.apache.ignite.internal.catalog.descriptors.CatalogHashIndexDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogIndexDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogSchemaDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableColumnDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogZoneDescriptor;
import org.apache.ignite.internal.catalog.events.CatalogEvent;
import org.apache.ignite.internal.catalog.events.CatalogEventParameters;
import org.apache.ignite.internal.catalog.storage.AlterColumnEntry;
import org.apache.ignite.internal.catalog.storage.AlterZoneEntry;
import org.apache.ignite.internal.catalog.storage.DropColumnsEntry;
import org.apache.ignite.internal.catalog.storage.DropIndexEntry;
import org.apache.ignite.internal.catalog.storage.DropTableEntry;
import org.apache.ignite.internal.catalog.storage.DropZoneEntry;
import org.apache.ignite.internal.catalog.storage.Fireable;
import org.apache.ignite.internal.catalog.storage.NewColumnsEntry;
import org.apache.ignite.internal.catalog.storage.NewIndexEntry;
import org.apache.ignite.internal.catalog.storage.NewTableEntry;
import org.apache.ignite.internal.catalog.storage.NewZoneEntry;
import org.apache.ignite.internal.catalog.storage.ObjectIdGenUpdateEntry;
import org.apache.ignite.internal.catalog.storage.UpdateEntry;
import org.apache.ignite.internal.catalog.storage.UpdateLog;
import org.apache.ignite.internal.catalog.storage.UpdateLog.OnUpdateHandler;
import org.apache.ignite.internal.catalog.storage.VersionedUpdate;
import org.apache.ignite.internal.distributionzones.DistributionZoneAlreadyExistsException;
import org.apache.ignite.internal.distributionzones.DistributionZoneBindTableException;
import org.apache.ignite.internal.distributionzones.DistributionZoneNotFoundException;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.manager.Producer;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.lang.ColumnAlreadyExistsException;
import org.apache.ignite.lang.ColumnNotFoundException;
import org.apache.ignite.lang.ErrorGroups;
import org.apache.ignite.lang.ErrorGroups.Common;
import org.apache.ignite.lang.ErrorGroups.DistributionZones;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.lang.IndexAlreadyExistsException;
import org.apache.ignite.lang.IndexNotFoundException;
import org.apache.ignite.lang.SchemaNotFoundException;
import org.apache.ignite.lang.TableAlreadyExistsException;
import org.apache.ignite.lang.TableNotFoundException;
import org.apache.ignite.sql.ColumnType;
import org.apache.ignite.sql.SqlException;
import org.jetbrains.annotations.Nullable;

/**
 * Catalog service implementation.
 */
public class CatalogManagerImpl extends Producer<CatalogEvent, CatalogEventParameters> implements CatalogManager {
    private static final int MAX_RETRY_COUNT = 10;

    /** Safe time to wait before new Catalog version activation. */
    private static final int DEFAULT_DELAY_DURATION = 0;

    /** The logger. */
    private static final IgniteLogger LOG = Loggers.forClass(CatalogManagerImpl.class);

    /** Versioned catalog descriptors. */
    private final NavigableMap<Integer, Catalog> catalogByVer = new ConcurrentSkipListMap<>();

    /** Versioned catalog descriptors sorted in chronological order. */
    private final NavigableMap<Long, Catalog> catalogByTs = new ConcurrentSkipListMap<>();

    private final UpdateLog updateLog;

    private final PendingComparableValuesTracker<Integer, Void> versionTracker = new PendingComparableValuesTracker<>(0);

    private final ClockWaiter clockWaiter;

    private final LongSupplier delayDurationMsSupplier;

    /**
     * Constructor.
     */
    public CatalogManagerImpl(UpdateLog updateLog, ClockWaiter clockWaiter) {
        this(updateLog, clockWaiter, DEFAULT_DELAY_DURATION);
    }

    /**
     * Constructor.
     */
    CatalogManagerImpl(UpdateLog updateLog, ClockWaiter clockWaiter, long delayDurationMs) {
        this(updateLog, clockWaiter, () -> delayDurationMs);
    }

    /**
     * Constructor.
     */
    public CatalogManagerImpl(UpdateLog updateLog, ClockWaiter clockWaiter, LongSupplier delayDurationMsSupplier) {
        this.updateLog = updateLog;
        this.clockWaiter = clockWaiter;
        this.delayDurationMsSupplier = delayDurationMsSupplier;
    }

    @Override
    public void start() {
        int objectIdGen = 0;

        // TODO: IGNITE-19082 Move default schema objects initialization to cluster init procedure.
        CatalogSchemaDescriptor schemaPublic = new CatalogSchemaDescriptor(
                objectIdGen++,
                DEFAULT_SCHEMA_NAME,
                new CatalogTableDescriptor[0],
                new CatalogIndexDescriptor[0]
        );

        CatalogZoneDescriptor defaultZone = new CatalogZoneDescriptor(
                objectIdGen++,
                DEFAULT_ZONE_NAME,
                25,
                1,
                INFINITE_TIMER_VALUE,
                INFINITE_TIMER_VALUE,
                INFINITE_TIMER_VALUE,
                DEFAULT_FILTER
        );

        registerCatalog(new Catalog(0, 0L, objectIdGen, List.of(defaultZone), List.of(schemaPublic)));

        updateLog.registerUpdateHandler(new OnUpdateHandlerImpl());

        updateLog.start();
    }

    @Override
    public void stop() throws Exception {
        updateLog.stop();
    }

    @Override
    public @Nullable CatalogTableDescriptor table(String tableName, long timestamp) {
        return catalogAt(timestamp).schema(DEFAULT_SCHEMA_NAME).table(tableName);
    }

    @Override
    public @Nullable CatalogTableDescriptor table(int tableId, long timestamp) {
        return catalogAt(timestamp).table(tableId);
    }

    @Override
    public @Nullable CatalogTableDescriptor table(int tableId, int catalogVersion) {
        return catalog(catalogVersion).table(tableId);
    }

    @Override
    public Collection<CatalogTableDescriptor> tables(int catalogVersion) {
        return catalog(catalogVersion).tables();
    }

    @Override
    public @Nullable CatalogIndexDescriptor index(String indexName, long timestamp) {
        return catalogAt(timestamp).schema(DEFAULT_SCHEMA_NAME).index(indexName);
    }

    @Override
    public @Nullable CatalogIndexDescriptor index(int indexId, long timestamp) {
        return catalogAt(timestamp).index(indexId);
    }

    @Override
    public @Nullable CatalogIndexDescriptor index(int indexId, int catalogVersion) {
        return catalog(catalogVersion).index(indexId);
    }

    @Override
    public Collection<CatalogIndexDescriptor> indexes(int catalogVersion) {
        return catalog(catalogVersion).indexes();
    }

    @Override
    public @Nullable CatalogSchemaDescriptor schema(int version) {
        Catalog catalog = catalog(version);

        if (catalog == null) {
            return null;
        }

        return catalog.schema(DEFAULT_SCHEMA_NAME);
    }

    @Override
    public @Nullable CatalogSchemaDescriptor schema(String schemaName, int version) {
        Catalog catalog = catalog(version);

        if (catalog == null) {
            return null;
        }

        return catalog.schema(schemaName == null ? DEFAULT_SCHEMA_NAME : schemaName);
    }

    @Override
    public CatalogZoneDescriptor zone(String zoneName, long timestamp) {
        return catalogAt(timestamp).zone(zoneName);
    }

    @Override
    public CatalogZoneDescriptor zone(int zoneId, long timestamp) {
        return catalogAt(timestamp).zone(zoneId);
    }

    @Override
    public @Nullable CatalogSchemaDescriptor activeSchema(long timestamp) {
        return catalogAt(timestamp).schema(DEFAULT_SCHEMA_NAME);
    }

    @Override
    public @Nullable CatalogSchemaDescriptor activeSchema(String schemaName, long timestamp) {
        return catalogAt(timestamp).schema(schemaName == null ? DEFAULT_SCHEMA_NAME : schemaName);
    }

    @Override
    public int activeCatalogVersion(long timestamp) {
        return catalogAt(timestamp).version();
    }

    @Override
    public int latestCatalogVersion() {
        return catalogByVer.lastEntry().getKey();
    }

    private Catalog catalog(int version) {
        return catalogByVer.get(version);
    }

    private Catalog catalogAt(long timestamp) {
        Entry<Long, Catalog> entry = catalogByTs.floorEntry(timestamp);

        if (entry == null) {
            throw new IllegalStateException("No valid schema found for given timestamp: " + timestamp);
        }

        return entry.getValue();
    }

    @Override
    public CompletableFuture<Void> createTable(CreateTableParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            if (schema.table(params.tableName()) != null) {
                throw new TableAlreadyExistsException(schema.name(), params.tableName());
            }

            validateCreateTableParams(params);

            CatalogZoneDescriptor zone = getZone(catalog, Objects.requireNonNullElse(params.zone(), DEFAULT_ZONE_NAME));

            int id = catalog.objectIdGenState();

            CatalogTableDescriptor table = CatalogUtils.fromParams(id++, zone.id(), params);

            CatalogHashIndexDescriptor pkIndex = createHashIndexDescriptor(table, id++, createPkIndexParams(params));

            return List.of(
                    new NewTableEntry(table),
                    new NewIndexEntry(pkIndex),
                    new ObjectIdGenUpdateEntry(id - catalog.objectIdGenState())
            );
        });
    }

    @Override
    public CompletableFuture<Void> dropTable(DropTableParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            List<UpdateEntry> updateEntries = new ArrayList<>();

            Arrays.stream(schema.indexes())
                    .filter(index -> index.tableId() == table.id())
                    .forEach(index -> updateEntries.add(new DropIndexEntry(index.id(), index.tableId())));

            updateEntries.add(new DropTableEntry(table.id()));

            return updateEntries;
        });
    }

    @Override
    public CompletableFuture<Void> addColumn(AlterTableAddColumnParams params) {
        if (params.columns().isEmpty()) {
            return completedFuture(null);
        }

        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            List<CatalogTableColumnDescriptor> columnDescriptors = new ArrayList<>();

            for (ColumnParams col : params.columns()) {
                if (table.column(col.name()) != null) {
                    throw new ColumnAlreadyExistsException(col.name());
                }

                columnDescriptors.add(CatalogUtils.fromParams(col));
            }

            return List.of(
                    new NewColumnsEntry(table.id(), columnDescriptors)
            );
        });
    }

    @Override
    public CompletableFuture<Void> dropColumn(AlterTableDropColumnParams params) {
        if (params.columns().isEmpty()) {
            return completedFuture(null);
        }

        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            validateAlterTableDropColumnParams(params, schema, table);

            return List.of(
                    new DropColumnsEntry(table.id(), params.columns())
            );
        });
    }

    @Override
    public CompletableFuture<Void> alterColumn(AlterColumnParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            CatalogTableColumnDescriptor origin = findTableColumn(table, params.columnName());

            CatalogTableColumnDescriptor target = createNewTableColumn(params, origin);

            if (origin.equals(target)) {
                // No modifications required.
                return Collections.emptyList();
            }

            boolean isPkColumn = table.isPrimaryKeyColumn(origin.name());

            validateAlterTableColumn(origin, target, isPkColumn);

            return List.of(
                    new AlterColumnEntry(table.id(), target)
            );
        });
    }

    @Override
    public CompletableFuture<Void> createIndex(CreateHashIndexParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            if (schema.index(params.indexName()) != null) {
                throw new IndexAlreadyExistsException(schema.name(), params.indexName());
            }

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            CatalogHashIndexDescriptor index = createHashIndexDescriptor(table, catalog.objectIdGenState(), params);

            return List.of(
                    new NewIndexEntry(index),
                    new ObjectIdGenUpdateEntry(1)
            );
        });
    }

    @Override
    public CompletableFuture<Void> createIndex(CreateSortedIndexParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            if (schema.index(params.indexName()) != null) {
                throw new IndexAlreadyExistsException(schema.name(), params.indexName());
            }

            CatalogTableDescriptor table = getTable(schema, params.tableName());

            validateCreateSortedIndexParams(params, table);

            CatalogIndexDescriptor index = CatalogUtils.fromParams(catalog.objectIdGenState(), table.id(), params);

            return List.of(
                    new NewIndexEntry(index),
                    new ObjectIdGenUpdateEntry(1)
            );
        });
    }

    @Override
    public CompletableFuture<Void> dropIndex(DropIndexParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            CatalogSchemaDescriptor schema = getSchema(catalog, params.schemaName());

            CatalogIndexDescriptor index = schema.index(params.indexName());

            if (index == null) {
                throw new IndexNotFoundException(schema.name(), params.indexName());
            }

            return List.of(
                    new DropIndexEntry(index.id(), index.tableId())
            );
        });
    }

    @Override
    public CompletableFuture<Void> createZone(CreateZoneParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            validateCreateZoneParams(params);

            if (catalog.zone(params.zoneName()) != null) {
                throw new DistributionZoneAlreadyExistsException(params.zoneName());
            }

            CatalogZoneDescriptor zone = CatalogUtils.fromParams(catalog.objectIdGenState(), params);

            return List.of(
                    new NewZoneEntry(zone),
                    new ObjectIdGenUpdateEntry(1)
            );
        });
    }

    @Override
    public CompletableFuture<Void> dropZone(DropZoneParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            validateDropZoneParams(params);

            CatalogZoneDescriptor zone = getZone(catalog, params.zoneName());

            if (zone.name().equals(DEFAULT_ZONE_NAME)) {
                throw new IgniteInternalException(
                        DistributionZones.ZONE_DROP_ERR,
                        "Default distribution zone can't be dropped"
                );
            }

            catalog.schemas().stream()
                    .flatMap(s -> Arrays.stream(s.tables()))
                    .filter(t -> t.zoneId() == zone.id())
                    .findAny()
                    .ifPresent(t -> {
                        throw new DistributionZoneBindTableException(zone.name(), t.name());
                    });

            return List.of(new DropZoneEntry(zone.id()));
        });
    }

    @Override
    public CompletableFuture<Void> renameZone(RenameZoneParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            validateRenameZoneParams(params);

            CatalogZoneDescriptor zone = getZone(catalog, params.zoneName());

            if (catalog.zone(params.newZoneName()) != null) {
                throw new DistributionZoneAlreadyExistsException(params.newZoneName());
            }

            if (zone.name().equals(DEFAULT_ZONE_NAME)) {
                throw new IgniteInternalException(
                        DistributionZones.ZONE_RENAME_ERR,
                        "Default distribution zone can't be renamed"
                );
            }

            CatalogZoneDescriptor descriptor = new CatalogZoneDescriptor(
                    zone.id(),
                    params.newZoneName(),
                    zone.partitions(),
                    zone.replicas(),
                    zone.dataNodesAutoAdjust(),
                    zone.dataNodesAutoAdjustScaleUp(),
                    zone.dataNodesAutoAdjustScaleDown(),
                    zone.filter()
            );

            return List.of(new AlterZoneEntry(descriptor));
        });
    }

    @Override
    public CompletableFuture<Void> alterZone(AlterZoneParams params) {
        return saveUpdateAndWaitForActivation(catalog -> {
            validateAlterZoneParams(params);

            CatalogZoneDescriptor zone = getZone(catalog, params.zoneName());

            Integer dataNodesAutoAdjust = Objects.requireNonNullElse(
                    params.dataNodesAutoAdjust(),
                    zone.dataNodesAutoAdjust()
            );

            Integer dataNodesAutoAdjustScaleUp = Objects.requireNonNullElse(
                    params.dataNodesAutoAdjustScaleUp(),
                    zone.dataNodesAutoAdjustScaleUp()
            );

            Integer dataNodesAutoAdjustScaleDown = Objects.requireNonNullElse(
                    params.dataNodesAutoAdjustScaleDown(),
                    zone.dataNodesAutoAdjustScaleDown()
            );

            validateZoneDataNodesAutoAdjustParametersCompatibility(
                    dataNodesAutoAdjust,
                    dataNodesAutoAdjustScaleUp,
                    dataNodesAutoAdjustScaleDown
            );

            CatalogZoneDescriptor descriptor = new CatalogZoneDescriptor(
                    zone.id(),
                    zone.name(),
                    Objects.requireNonNullElse(params.partitions(), zone.partitions()),
                    Objects.requireNonNullElse(params.replicas(), zone.replicas()),
                    dataNodesAutoAdjust,
                    dataNodesAutoAdjustScaleUp,
                    dataNodesAutoAdjustScaleDown,
                    Objects.requireNonNullElse(params.filter(), zone.filter())
            );

            return List.of(new AlterZoneEntry(descriptor));
        });
    }

    private void registerCatalog(Catalog newCatalog) {
        catalogByVer.put(newCatalog.version(), newCatalog);
        catalogByTs.put(newCatalog.time(), newCatalog);
    }

    private CompletableFuture<Void> saveUpdateAndWaitForActivation(UpdateProducer updateProducer) {
        return saveUpdate(updateProducer, 0)
                .thenCompose(newVersion -> {
                    Catalog catalog = catalogByVer.get(newVersion);

                    HybridTimestamp activationTs = HybridTimestamp.hybridTimestamp(catalog.time());
                    HybridTimestamp clusterWideEnsuredActivationTs = activationTs.addPhysicalTime(
                            HybridTimestamp.maxClockSkew()
                    );

                    return clockWaiter.waitFor(clusterWideEnsuredActivationTs);
                });
    }

    /**
     * Attempts to save a versioned update using a CAS-like logic. If the attempt fails, makes more attempts
     * until the max retry count is reached.
     *
     * @param updateProducer Supplies simple updates to include into a versioned update to install.
     * @param attemptNo Ordinal number of an attempt.
     * @return Future that completes with the new Catalog version (if update was saved successfully) or an exception, otherwise.
     */
    private CompletableFuture<Integer> saveUpdate(UpdateProducer updateProducer, int attemptNo) {
        if (attemptNo >= MAX_RETRY_COUNT) {
            return failedFuture(new IgniteInternalException(Common.INTERNAL_ERR, "Max retry limit exceeded: " + attemptNo));
        }

        Catalog catalog = catalogByVer.lastEntry().getValue();

        List<UpdateEntry> updates;
        try {
            updates = updateProducer.get(catalog);
        } catch (Exception ex) {
            return failedFuture(ex);
        }

        if (updates.isEmpty()) {
            return completedFuture(catalog.version());
        }

        int newVersion = catalog.version() + 1;

        return updateLog.append(new VersionedUpdate(newVersion, delayDurationMsSupplier.getAsLong(), updates))
                .thenCompose(result -> versionTracker.waitFor(newVersion).thenApply(none -> result))
                .thenCompose(result -> {
                    if (result) {
                        return completedFuture(newVersion);
                    }

                    return saveUpdate(updateProducer, attemptNo + 1);
                });
    }

    class OnUpdateHandlerImpl implements OnUpdateHandler {
        @Override
        public void handle(VersionedUpdate update, HybridTimestamp metaStorageUpdateTimestamp, long causalityToken) {
            int version = update.version();
            Catalog catalog = catalogByVer.get(version - 1);

            assert catalog != null : version - 1;

            for (UpdateEntry entry : update.entries()) {
                catalog = entry.applyUpdate(catalog);
            }

            catalog = applyUpdateFinal(catalog, update, metaStorageUpdateTimestamp);

            registerCatalog(catalog);

            List<CompletableFuture<?>> eventFutures = new ArrayList<>(update.entries().size());

            for (UpdateEntry entry : update.entries()) {
                if (entry instanceof Fireable) {
                    Fireable fireEvent = (Fireable) entry;

                    eventFutures.add(fireEvent(
                            fireEvent.eventType(),
                            fireEvent.createEventParameters(causalityToken, version)
                    ));
                }
            }

            CompletableFuture.allOf(eventFutures.toArray(CompletableFuture[]::new))
                    .whenComplete((ignore, err) -> {
                        if (err != null) {
                            LOG.warn("Failed to apply catalog update.", err);
                            //TODO: IGNITE-14611 Pass exception to an error handler because catalog got into inconsistent state.
                        }

                        versionTracker.update(version, null);
                    });
        }
    }

    private static void throwUnsupportedDdl(String msg, Object... params) {
        throw new SqlException(STMT_VALIDATION_ERR, format(msg, params));
    }

    @FunctionalInterface
    interface UpdateProducer {
        List<UpdateEntry> get(Catalog catalog);
    }

    private static Catalog applyUpdateFinal(Catalog catalog, VersionedUpdate update, HybridTimestamp metaStorageUpdateTimestamp) {
        long activationTimestamp = metaStorageUpdateTimestamp.addPhysicalTime(update.delayDurationMs()).longValue();

        return new Catalog(
                update.version(),
                activationTimestamp,
                catalog.objectIdGenState(),
                catalog.zones(),
                catalog.schemas()
        );
    }

    private static CatalogSchemaDescriptor getSchema(Catalog catalog, @Nullable String schemaName) {
        schemaName = Objects.requireNonNullElse(schemaName, DEFAULT_SCHEMA_NAME);

        CatalogSchemaDescriptor schema = catalog.schema(schemaName);

        if (schema == null) {
            throw new SchemaNotFoundException(schemaName);
        }

        return schema;
    }

    private static CatalogTableDescriptor getTable(CatalogSchemaDescriptor schema, String tableName) {
        CatalogTableDescriptor table = schema.table(Objects.requireNonNull(tableName, "tableName"));

        if (table == null) {
            throw new TableNotFoundException(schema.name(), tableName);
        }

        return table;
    }

    private static CatalogZoneDescriptor getZone(Catalog catalog, String zoneName) {
        zoneName = Objects.requireNonNull(zoneName, "zoneName");

        CatalogZoneDescriptor zone = catalog.zone(zoneName);

        if (zone == null) {
            throw new DistributionZoneNotFoundException(zoneName);
        }

        return zone;
    }

    private static CatalogTableColumnDescriptor findTableColumn(CatalogTableDescriptor table, String columnName) {
        return table.columns().stream()
                .filter(desc -> desc.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new ColumnNotFoundException(columnName));
    }

    private static CatalogTableColumnDescriptor createNewTableColumn(AlterColumnParams params, CatalogTableColumnDescriptor origin) {
        return new CatalogTableColumnDescriptor(
                origin.name(),
                Objects.requireNonNullElse(params.type(), origin.type()),
                !Objects.requireNonNullElse(params.notNull(), !origin.nullable()),
                Objects.requireNonNullElse(params.precision(), origin.precision()),
                Objects.requireNonNullElse(params.scale(), origin.scale()),
                Objects.requireNonNullElse(params.length(), origin.length()),
                Objects.requireNonNullElse(params.defaultValue(origin.type()), origin.defaultValue())
        );
    }

    private static void validateCreateTableParams(CreateTableParams params) {
        // Table must have columns.
        if (params.columns().isEmpty()) {
            throw new IgniteInternalException(ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR, "Table must include at least one column.");
        }

        // Column names must be unique.
        params.columns().stream()
                .map(ColumnParams::name)
                .filter(Predicate.not(new HashSet<>()::add))
                .findAny()
                .ifPresent(columnName -> {
                    throw new IgniteInternalException(
                            ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                            "Can't create table with duplicate columns: {}",
                            params.columns().stream().map(ColumnParams::name).collect(joining(", "))
                    );
                });

        // Table must have PK columns.
        if (params.primaryKeyColumns().isEmpty()) {
            throw new IgniteInternalException(
                    ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                    "Table without primary key is not supported."
            );
        }

        // PK columns must be valid columns.
        Set<String> columns = params.columns().stream().map(ColumnParams::name).collect(Collectors.toSet());
        params.primaryKeyColumns().stream()
                .filter(Predicate.not(columns::contains))
                .findAny()
                .ifPresent(columnName -> {
                    throw new IgniteInternalException(
                            ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                            "Invalid primary key columns: {}",
                            params.columns().stream().map(ColumnParams::name).collect(joining(", "))
                    );
                });

        // PK column names must be unique.
        params.primaryKeyColumns().stream()
                .filter(Predicate.not(new HashSet<>()::add))
                .findAny()
                .ifPresent(columnName -> {
                    throw new IgniteInternalException(
                            ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                            "Primary key columns contains duplicates: {}",
                            String.join(", ", params.primaryKeyColumns())
                    );
                });

        List<String> colocationCols = params.colocationColumns();
        if (colocationCols != null) {
            // Colocation columns must be unique
            colocationCols.stream()
                    .filter(Predicate.not(new HashSet<>()::add))
                    .findAny()
                    .ifPresent(columnName -> {
                        throw new IgniteInternalException(
                                ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                                "Colocation columns contains duplicates: {}",
                                String.join(", ", colocationCols)
                        );
                    });

            // Colocation column must be valid PK column
            Set<String> pkColumns = new HashSet<>(params.primaryKeyColumns());
            List<String> outstandingColumns = colocationCols.stream()
                    .filter(Predicate.not(pkColumns::contains))
                    .collect(Collectors.toList());
            if (!outstandingColumns.isEmpty()) {
                throw new IgniteInternalException(
                        ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                        "Colocation columns must be subset of primary key: outstandingColumns=" + outstandingColumns);
            }
        }
    }

    private static void validateAlterTableDropColumnParams(
            AlterTableDropColumnParams params,
            CatalogSchemaDescriptor schema,
            CatalogTableDescriptor table
    ) {
        for (String columnName : params.columns()) {
            if (table.column(columnName) == null) {
                throw new ColumnNotFoundException(schema.name(), params.tableName(), columnName);
            }

            if (table.isPrimaryKeyColumn(columnName)) {
                throw new SqlException(
                        STMT_VALIDATION_ERR,
                        "Can't drop primary key column: [name=" + columnName + ']'
                );
            }
        }

        Arrays.stream(schema.indexes())
                .filter(index -> index.tableId() == table.id())
                .forEach(index -> params.columns().stream()
                        .filter(index::hasColumn)
                        .findAny()
                        .ifPresent(columnName -> {
                            throw new SqlException(
                                    STMT_VALIDATION_ERR,
                                    format("Can't drop indexed column: [columnName={}, indexName={}]", columnName, index.name()));
                        }));
    }

    private static void validateCreateHashIndexParams(CreateHashIndexParams params, CatalogTableDescriptor table) {
        validateIndexColumns(params.columns(), table);
    }

    private static void validateCreateSortedIndexParams(CreateSortedIndexParams params, CatalogTableDescriptor table) {
        validateIndexColumns(params.columns(), table);

        if (params.collations().size() != params.columns().size()) {
            throw new IgniteInternalException(
                    ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                    "Columns collations doesn't match number of columns."
            );
        }
    }

    private static void validateIndexColumns(List<String> indexColumns, CatalogTableDescriptor table) {
        if (indexColumns.isEmpty()) {
            throw new IgniteInternalException(
                    ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                    "No index columns was specified."
            );
        }

        Predicate<String> duplicateValidator = Predicate.not(new HashSet<>()::add);

        for (String columnName : indexColumns) {
            CatalogTableColumnDescriptor columnDescriptor = table.columnDescriptor(columnName);

            if (columnDescriptor == null) {
                throw new ColumnNotFoundException(columnName);
            } else if (duplicateValidator.test(columnName)) {
                throw new IgniteInternalException(
                        ErrorGroups.Index.INVALID_INDEX_DEFINITION_ERR,
                        "Can't create index on duplicate columns: {}",
                        String.join(", ", indexColumns)
                );
            }
        }
    }

    private static void validateAlterTableColumn(
            CatalogTableColumnDescriptor origin,
            CatalogTableColumnDescriptor target,
            boolean isPkColumn
    ) {
        if (origin.nullable() != target.nullable()) {
            if (isPkColumn) {
                throwUnsupportedDdl("Cannot change NOT NULL for the primary key column '{}'.", origin.name());
            }

            if (origin.nullable()) {
                throwUnsupportedDdl("Cannot set NOT NULL for column '{}'.", origin.name());
            }
        }

        if (origin.scale() != target.scale()) {
            throwUnsupportedDdl("Cannot change scale for column '{}'.", origin.name());
        }

        if (origin.type() != target.type()) {
            if (isPkColumn) {
                throwUnsupportedDdl("Cannot change data type for primary key column '{}'.", origin.name());
            }

            if (!CatalogUtils.isSupportedColumnTypeChange(origin.type(), target.type())) {
                throwUnsupportedDdl("Cannot change data type for column '{}' [from={}, to={}].",
                        origin.name(), origin.type(), target.type());
            }
        }

        if (origin.length() != target.length() && target.type() != ColumnType.STRING && target.type() != ColumnType.BYTE_ARRAY) {
            throwUnsupportedDdl("Cannot change length for column '{}'.", origin.name());
        } else if (target.length() < origin.length()) {
            throwUnsupportedDdl("Cannot decrease length to {} for column '{}'.", target.length(), origin.name());
        }

        if (origin.precision() != target.precision() && target.type() != ColumnType.DECIMAL) {
            throwUnsupportedDdl("Cannot change precision for column '{}'.", origin.name());
        } else if (target.precision() < origin.precision()) {
            throwUnsupportedDdl("Cannot decrease precision to {} for column '{}'.", target.precision(), origin.name());
        }
    }

    private static CreateHashIndexParams createPkIndexParams(CreateTableParams params) {
        return CreateHashIndexParams.builder()
                .schemaName(params.schemaName())
                .tableName(params.tableName())
                .indexName(params.tableName() + "_PK")
                .columns(params.primaryKeyColumns())
                .unique()
                .build();
    }

    private static CatalogHashIndexDescriptor createHashIndexDescriptor(
            CatalogTableDescriptor table,
            int indexId,
            CreateHashIndexParams params
    ) {
        validateCreateHashIndexParams(params, table);

        return CatalogUtils.fromParams(indexId, table.id(), params);
    }
}