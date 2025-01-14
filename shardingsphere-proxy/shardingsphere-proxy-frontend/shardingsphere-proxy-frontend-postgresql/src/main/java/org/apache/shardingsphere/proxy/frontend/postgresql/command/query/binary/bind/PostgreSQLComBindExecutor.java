/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.frontend.postgresql.command.query.binary.bind;

import lombok.Getter;
import org.apache.shardingsphere.db.protocol.binary.BinaryCell;
import org.apache.shardingsphere.db.protocol.binary.BinaryRow;
import org.apache.shardingsphere.db.protocol.packet.DatabasePacket;
import org.apache.shardingsphere.db.protocol.postgresql.constant.PostgreSQLBinaryColumnType;
import org.apache.shardingsphere.db.protocol.postgresql.packet.PostgreSQLPacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.PostgreSQLColumnDescription;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.PostgreSQLRowDescriptionPacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.binary.bind.PostgreSQLBinaryResultSetRowPacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.binary.bind.PostgreSQLBindCompletePacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.binary.bind.PostgreSQLComBindPacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.command.query.text.PostgreSQLDataRowPacket;
import org.apache.shardingsphere.db.protocol.postgresql.packet.generic.PostgreSQLCommandCompletePacket;
import org.apache.shardingsphere.infra.database.type.DatabaseTypeRegistry;
import org.apache.shardingsphere.infra.parser.ShardingSphereSQLParserEngine;
import org.apache.shardingsphere.proxy.backend.communication.DatabaseCommunicationEngine;
import org.apache.shardingsphere.proxy.backend.communication.DatabaseCommunicationEngineFactory;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.response.data.QueryResponseRow;
import org.apache.shardingsphere.proxy.backend.response.data.impl.BinaryQueryResponseCell;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.impl.QueryHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandler;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandlerFactory;
import org.apache.shardingsphere.proxy.frontend.command.executor.QueryCommandExecutor;
import org.apache.shardingsphere.proxy.frontend.command.executor.ResponseType;
import org.apache.shardingsphere.proxy.frontend.postgresql.command.query.PostgreSQLCommand;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.tcl.TCLStatement;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command bind executor for PostgreSQL.
 */
public final class PostgreSQLComBindExecutor implements QueryCommandExecutor {
    
    private final PostgreSQLComBindPacket packet;
    
    private final DatabaseCommunicationEngine databaseCommunicationEngine;
    
    private final TextProtocolBackendHandler textProtocolBackendHandler;
    
    @Getter
    private volatile ResponseType responseType;
    
    public PostgreSQLComBindExecutor(final PostgreSQLComBindPacket packet, final BackendConnection backendConnection) throws SQLException {
        this.packet = packet;
        if (null == packet.getSql()) {
            databaseCommunicationEngine = null;
            textProtocolBackendHandler = null;
            return;
        }
        ShardingSphereSQLParserEngine sqlStatementParserEngine = new ShardingSphereSQLParserEngine(DatabaseTypeRegistry.getTrunkDatabaseTypeName(
                ProxyContext.getInstance().getMetaDataContexts().getMetaData(backendConnection.getSchemaName()).getResource().getDatabaseType()));
        SQLStatement sqlStatement = sqlStatementParserEngine.parse(packet.getSql(), true);
        if (sqlStatement instanceof TCLStatement) {
            textProtocolBackendHandler = TextProtocolBackendHandlerFactory.newInstance(DatabaseTypeRegistry.getActualDatabaseType("PostgreSQL"), packet.getSql(), backendConnection);
            databaseCommunicationEngine = null;
            return;
        }
        textProtocolBackendHandler = null;
        databaseCommunicationEngine = DatabaseCommunicationEngineFactory.getInstance().newBinaryProtocolInstance(sqlStatement, packet.getSql(), packet.getParameters(), backendConnection);
    }
    
    @Override
    public Collection<DatabasePacket<?>> execute() throws SQLException {
        List<DatabasePacket<?>> result = new LinkedList<>();
        result.add(new PostgreSQLBindCompletePacket());
        if (null == databaseCommunicationEngine && null == textProtocolBackendHandler) {
            return result;
        }
        ResponseHeader responseHeader = null != databaseCommunicationEngine ? databaseCommunicationEngine.execute() : textProtocolBackendHandler.execute();
        if (responseHeader instanceof QueryResponseHeader) {
            createQueryPacket((QueryResponseHeader) responseHeader).ifPresent(result::add);
        }
        if (responseHeader instanceof UpdateResponseHeader) {
            responseType = ResponseType.UPDATE;
            result.add(createUpdatePacket((UpdateResponseHeader) responseHeader));
        }
        return result;
    }
    
    private Optional<PostgreSQLRowDescriptionPacket> createQueryPacket(final QueryResponseHeader queryResponseHeader) {
        Collection<PostgreSQLColumnDescription> columnDescriptions = createColumnDescriptions(queryResponseHeader);
        if (packet.isBinaryRowData()) {
            return Optional.empty();
        }
        responseType = ResponseType.QUERY;
        return Optional.of(new PostgreSQLRowDescriptionPacket(columnDescriptions.size(), columnDescriptions));
    }
    
    private Collection<PostgreSQLColumnDescription> createColumnDescriptions(final QueryResponseHeader queryResponseHeader) {
        Collection<PostgreSQLColumnDescription> result = new LinkedList<>();
        int columnIndex = 0;
        for (QueryHeader each : queryResponseHeader.getQueryHeaders()) {
            result.add(new PostgreSQLColumnDescription(each.getColumnName(), ++columnIndex, each.getColumnType(), each.getColumnLength(), each.getColumnTypeName()));
        }
        return result;
    }
    
    private PostgreSQLCommandCompletePacket createUpdatePacket(final UpdateResponseHeader updateResponseHeader) {
        return new PostgreSQLCommandCompletePacket(PostgreSQLCommand.valueOf(updateResponseHeader.getSqlStatement().getClass()).map(Enum::name).orElse(""), updateResponseHeader.getUpdateCount());
    }
    
    @Override
    public boolean next() throws SQLException {
        return null != databaseCommunicationEngine && databaseCommunicationEngine.next();
    }
    
    @Override
    public PostgreSQLPacket getQueryRowPacket() throws SQLException {
        QueryResponseRow queryResponseRow = databaseCommunicationEngine.getQueryResponseRow();
        return packet.isBinaryRowData() ? new PostgreSQLBinaryResultSetRowPacket(createBinaryRow(queryResponseRow)) : new PostgreSQLDataRowPacket(queryResponseRow.getData());
    }
    
    private BinaryRow createBinaryRow(final QueryResponseRow queryResponseRow) {
        return new BinaryRow(queryResponseRow.getCells().stream().map(
            each -> new BinaryCell(PostgreSQLBinaryColumnType.valueOfJDBCType(((BinaryQueryResponseCell) each).getJdbcType()), each.getData())).collect(Collectors.toList()));
    }
}
