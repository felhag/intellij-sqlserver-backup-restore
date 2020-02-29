package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.remote.jdbc.RemoteConnection;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.remote.jdbc.RemoteStatement;
import com.intellij.database.util.GuardedRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class Connection implements AutoCloseable {
    private final GuardedRef<DatabaseConnection> ref;
    private final RemoteConnection remoteConnection;
    private Consumer<SQLWarning> warningConsumer;
    private RemoteStatement statement;
    private boolean closed = false;

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        set(null);
        ref.close();
    }

    public Connection withMessages(Consumer<SQLWarning> consumer) {
        warningConsumer = consumer;
        return this;
    }

    private RemoteStatement set(RemoteStatement s) {
        try {
            if (statement != null && !statement.isClosed()) {
                statement.close();
            }
        } catch (Exception e) {
            log.error("Unable to close statement", e);
        }
        statement = s;
        return s;
    }

    public void cancel() {
        try {
            if (statement != null && !statement.isClosed()) {
                statement.cancel();
            }
        } catch (Exception e) {
            log.error("Unable to cancel statement");
        }
    }

    public Optional<RemoteStatement> createStatement() {
        RemoteStatement result = null;
        try {
            result = set(remoteConnection.createStatement());
        } catch (Exception e) {
            log.error("Unable to get statement", e);
        }
        return Optional.ofNullable(result);
    }

    public Optional<List<Map<String, Object>>> getResult(String query) {
        return withResult(query, rs -> {
            var result = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i < rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
            return result;
        });
    }

    public <T> Optional<T> getSingle(Class<T> resultType, String query, int column) {
        return withResult(query, rs -> {
            Object result = null;
            if (rs.next()) {
                result = rs.getObject(column);
            }
            return resultType.cast(result);
        });
    }

    public <T> Optional<T> getSingle(Class<T> resultType, String query, String column) {
        return withResult(query, rs -> {
            Object result = null;
            if (rs.next()) {
                result = rs.getObject(column);
            }
            return resultType.cast(result);
        });
    }

    public void execute(String query) {
        withResult(query, x -> null);
    }

    private <T> Optional<T> withResult(String query, ResultSetFunction<T> fnc) {
        return createStatement().flatMap(s -> {
                    var reader = WarningReader.ifNeeded(s, warningConsumer);
                    warningConsumer = null;

                    try {
                        if (s.execute(query)) {
                            return Optional.of(fnc.apply(s.getResultSet()));
                        }
                    } catch (Exception e) {
                        reader.ifPresentOrElse(r -> r.consume(-1, "Error while reading", e),
                                () -> log.error("Unable to execute and get result for '{}'", query));
                    } finally {
                        reader.ifPresent(WarningReader::stop);
                        try {
                            var warnings = s.getAllWarnings();
                            if (!warnings.isEmpty()) {
                                log.warn("Warnings were given while executing that weren't consumed {}: {}", query, warnings);
                            }

                            set(null);
                        } catch (Exception e) {
                            log.error("Unable to close statement for '{}'", query);
                        }
                    }
                    return Optional.empty();
                }
        );
    }

    public interface ResultSetFunction<T> {
        T apply(RemoteResultSet rs) throws Exception; // NOSONAR
    }
}
