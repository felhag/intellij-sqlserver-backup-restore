package dev.niels.sqlbackuprestore.query;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.remote.jdbc.RemoteBlob;
import com.intellij.database.remote.jdbc.RemoteConnection;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.remote.jdbc.RemoteStatement;
import com.intellij.database.remote.jdbc.impl.ReflectionHelper;
import com.intellij.database.util.GuardedRef;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class Connection implements AutoCloseable {
    private final GuardedRef<DatabaseConnection> ref;
    private final RemoteConnection remoteConnection;
    private Consumer<SQLWarning> warningConsumer;
    private RemoteStatement statement;
    private boolean closed = false;

    public Connection takeOver() {
        if (closed) {
            log.error("Unable to take over connection that is already closed");
            throw new IllegalStateException("Unable to take over connection that is already closed");
        }
        closed = true;
        return new Connection(ref, remoteConnection);
    }

    @Override
    public void close() {
        set(null);
        if (closed) {
            return;
        }
        closed = true;
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
        if (closed) {
            throw new IllegalStateException("Requesting statement from closed connection");
        }

        RemoteStatement result = null;
        try {
            result = set(remoteConnection.createStatement());
        } catch (Exception e) {
            log.error("Unable to get statement", e);
        }
        return Optional.ofNullable(result);
    }

    public Optional<List<Map<String, Object>>> getResult(String query) {
        var r = withResult(query, rs -> {
            List<Map<String, Object>> result = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i < rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
            return result;
        });
        r.getLeft().close();
        return r.getRight();
    }

    public <T> Optional<T> getSingle(Class<T> resultType, String query, String column) {
        var r = withResult(query, rs -> {
            Object result = null;
            if (rs.next()) {
                if (resultType == BlobWrapper.class) {
                    result = new BlobWrapper(rs.getBlob(column));
                    statement = null; // Prevent closing statement until the blob is read
                } else {
                    result = rs.getObject(column);
                }
            }
            return resultType.cast(result);
        });
        r.getRight().filter(BlobWrapper.class::isInstance).ifPresentOrElse(bw -> ((BlobWrapper) bw).setWrapper(r.getLeft()), r.getLeft()::close);
        return r.getRight();
    }

    public void execute(String query) {
        withResult(query, x -> null);
    }

    private <T> Pair<ClosableWrapper, Optional<T>> withResult(String query, ResultSetFunction<T> fnc) {
        var close = new ClosableWrapper();
        return Pair.of(close, createStatement().flatMap(s -> {
                    close.add(s);

                    var reader = WarningReader.ifNeeded(s, warningConsumer);
                    warningConsumer = null;
                    try {
                        if (s.execute(query)) {
                            var results = s.getResultSet();
                            close.add(results);

                            return Optional.of(fnc.apply(results));
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
                        } catch (Exception e) {
                            log.error("Unable to close statement for '{}'", query);
                        }
                    }
                    return Optional.empty();
                }
        ));
    }

    @Slf4j
    private static class ClosableWrapper implements AutoCloseable {
        private final Set<Object> closables = new HashSet<>();

        public void add(Object c) {
            closables.add(c);
        }

        @Override
        public void close() {
            for (Object closable : closables) {
                try {
                    ReflectionHelper.tryInvokeMethod(closable, "close", null, null);
                } catch (Exception e) {
                    log.error("Unable to close {}", closable);
                }
            }
        }
    }

    /**
     * Keeps everything that's needed to keep a blob alive next to the blob so they won't be garbage collected
     */
    @RequiredArgsConstructor
    public static class BlobWrapper implements AutoCloseable {
        @Getter
        private final RemoteBlob blob;
        @Setter(AccessLevel.PRIVATE)
        private ClosableWrapper wrapper;

        @Override
        public void close() throws Exception {
            try {
                blob.free();
            } catch (Exception e) {
                // Freeing a blob can run into an AbstractMethodError, that sucks...
            }
            wrapper.close();
        }
    }

    public interface ResultSetFunction<T> {
        T apply(RemoteResultSet rs) throws Exception; // NOSONAR
    }
}
