/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.data.GenericRow;
import org.apache.paimon.types.DataField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A data change message from the CDC source. Compared to {@link CdcRecord}, MultiplexCdcRecord
 * contains database and table information.
 */
public class CdcMultiplexRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(CdcMultiplexRecord.class);

    private final String databaseName;
    private final String tableName;
    private final CdcRecord record;

    public CdcMultiplexRecord(String databaseName, String tableName, CdcRecord record) {
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.record = record;
    }

    public static CdcMultiplexRecord fromCdcRecord(
            String databaseName, String tableName, CdcRecord record) {
        return new CdcMultiplexRecord(databaseName, tableName, record);
    }

    public GenericRow projectAsInsert(List<DataField> dataFields) {
        return record.projectAsInsert(dataFields);
    }

    public Optional<GenericRow> toGenericRow(List<DataField> dataFields) {
        return record.toGenericRow(dataFields);
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CdcMultiplexRecord)) {
            return false;
        }

        CdcMultiplexRecord that = (CdcMultiplexRecord) o;
        return Objects.equals(databaseName, that.databaseName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(record, that.record);
    }

    @Override
    public String toString() {
        return databaseName + "." + tableName + " " + record.toString();
    }
}
