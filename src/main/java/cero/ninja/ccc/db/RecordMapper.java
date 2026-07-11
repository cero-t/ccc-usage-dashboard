package cero.ninja.ccc.db;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class RecordMapper {

    private static final ConcurrentHashMap<Class<?>, RowMapper<?>> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T> RowMapper<T> of(Class<T> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Auto-mapping supports only Java records: " + type.getName());
        }
        return (RowMapper<T>) CACHE.computeIfAbsent(type, RecordMapper::build);
    }

    private static <T> RowMapper<T> build(Class<T> type) {
        var components = type.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        String[] names = new String[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            names[i] = components[i].getName();
        }
        Constructor<T> ctor;
        try {
            ctor = type.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Canonical constructor not found for record " + type.getName(), e);
        }
        ctor.setAccessible(true);
        return (rs, rowNum) -> instantiate(ctor, names, rs);
    }

    private static <T> T instantiate(Constructor<T> ctor, String[] compNames, ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Integer> columnIndex = new HashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            columnIndex.put(md.getColumnLabel(i).toLowerCase(), i);
        }
        Object[] args = new Object[compNames.length];
        Class<?>[] paramTypes = ctor.getParameterTypes();
        for (int i = 0; i < compNames.length; i++) {
            String name = compNames[i];
            Integer idx = columnIndex.get(name.toLowerCase());
            if (idx == null) {
                idx = columnIndex.get(camelToSnake(name));
            }
            if (idx == null) {
                throw new SQLException("Column for record component '" + name + "' not found in result set");
            }
            args[i] = readValue(rs, idx, paramTypes[i]);
        }
        try {
            return ctor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to instantiate " + ctor.getDeclaringClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Record canonical constructor threw", e.getCause());
        }
    }

    private static Object readValue(ResultSet rs, int idx, Class<?> target) throws SQLException {
        if (target == String.class) return rs.getString(idx);
        if (target == int.class) return rs.getInt(idx);
        if (target == Integer.class) {
            int value = rs.getInt(idx);
            return rs.wasNull() ? null : value;
        }
        if (target == long.class) return rs.getLong(idx);
        if (target == Long.class) {
            long value = rs.getLong(idx);
            return rs.wasNull() ? null : value;
        }
        if (target == double.class) return rs.getDouble(idx);
        if (target == Double.class) {
            double value = rs.getDouble(idx);
            return rs.wasNull() ? null : value;
        }
        if (target == float.class) return rs.getFloat(idx);
        if (target == Float.class) {
            float value = rs.getFloat(idx);
            return rs.wasNull() ? null : value;
        }
        if (target == boolean.class) return rs.getBoolean(idx);
        if (target == Boolean.class) {
            boolean value = rs.getBoolean(idx);
            return rs.wasNull() ? null : value;
        }
        if (target == Instant.class) {
            var ts = rs.getTimestamp(idx);
            return ts == null ? null : ts.toInstant();
        }
        if (target == OffsetDateTime.class) {
            return rs.getObject(idx, OffsetDateTime.class);
        }
        return rs.getObject(idx, target);
    }

    private static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private RecordMapper() {}
}
