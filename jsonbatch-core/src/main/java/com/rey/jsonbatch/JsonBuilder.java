package com.rey.jsonbatch;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.rey.jsonbatch.function.Function;
import com.rey.jsonbatch.parser.Parser;
import com.rey.jsonbatch.parser.Token;
import com.rey.jsonbatch.parser.TokenValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unchecked")
public class JsonBuilder {

    private Logger logger = LoggerFactory.getLogger(JsonBuilder.class);

    private static final String PATTERN_PARAM_DELIMITER = "\\s{1,}";
    private static final String PATTERN_INLINE_VARIABLE = "@\\{(((?!@\\{).)*)}@";

    private static final String KEY_ARRAY_PATH = "__array_path";

    private List<Function> functions = new ArrayList<>();

    private Parser parser = new Parser();

    public JsonBuilder(Function... functions) {
        Collections.addAll(this.functions, functions);
    }

    public Object build(Object schema, DocumentContext context) {
        logger.info("Build schema: {}", schema);
        if (schema instanceof String)
            return buildNode((String) schema, context);
        if (schema instanceof Map)
            return buildObject((Map) schema, context);
        if (schema instanceof Collection)
            return buildList((Collection) schema, context);
        logger.error("Unsupported class: {}", schema.getClass());
        throw new IllegalArgumentException("Unsupported class: " + schema.getClass());
    }

    private Object buildNode(String schema, DocumentContext context) {
        String[] parts = schema.split(PATTERN_PARAM_DELIMITER, 2);
        if (parts.length < 2) {
            logger.error("Invalid node schema: {}", schema);
            throw new IllegalArgumentException("Invalid schema: " + schema);
        }

        Type type = Type.from(parts[0]);

        List<TokenValue> tokenValues = parser.parse(parts[1]);
        TokenValue firstToken = tokenValues.get(0);
        if(firstToken.getToken() == Token.JSON_PATH)
            return buildNodeFromJsonPath(type, context, firstToken.getValue());
        else if(firstToken.getToken() == Token.FUNC){
            return buildNodeFromFunction(type, tokenValues, context);
        }

        return buildNodeFromRawData(type, firstToken.getValue(), context);
    }

    private Map buildObject(Map<String, Object> schema, DocumentContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        schema.forEach((key, childSchema) -> {
            if (isValidKey(key)) {
                logger.info("Build for [{}] key with schema: {}", key, childSchema);
                if (childSchema instanceof String)
                    result.put(key, buildNode((String) childSchema, context));
                if (childSchema instanceof Map)
                    result.put(key, buildObject((Map) childSchema, context));
                if (childSchema instanceof List)
                    result.put(key, buildList((List) childSchema, context));
            }
        });
        return result;
    }

    private List buildList(Collection schema, DocumentContext context) {
        List<Object> result = new ArrayList<>();
        for (Map<String, Object> childSchema : (Iterable<Map<String, Object>>) schema) {
            logger.info("Build items with schema: {}", childSchema);
            String arrayPath = (String) childSchema.get(KEY_ARRAY_PATH);
            if(arrayPath == null) {
                logger.error("Missing array path in child schema");
                throw new IllegalArgumentException("Missing array path in child schema");
            }
            List<Object> items = context.read(arrayPath);
            result.addAll(items.stream()
                    .map(object -> build(childSchema, JsonPath.using(context.configuration()).parse(object)))
                    .collect(Collectors.toList()));
        }
        return result;
    }

    private Object buildNodeFromJsonPath(Type type, DocumentContext context, String jsonPath) {
        logger.trace("build Node with [{}] jsonPath to [{}] type", jsonPath, type);
        Object object = context.read(jsonPath);
        if (object == null)
            return null;

        if (!type.isArray) {
            if (object instanceof List) {
                List list = (List) object;
                object = list.isEmpty() ? null : list.get(0);
            }
            return castToType(object, type);
        } else {
            if (!(object instanceof List)) {
                object = Collections.singleton(object);
            }
            return ((List) object).stream()
                    .map(obj -> castToType(obj, type.elementType))
                    .collect(Collectors.toList());
        }
    }

    private Object buildNodeFromFunction(Type type, List<TokenValue> tokenValues, DocumentContext context) {
        TokenValue tokenValue = tokenValues.remove(0);
        final String funcName = tokenValue.getValue();
        logger.trace("build Node with [{}] function to [{}] type", funcName, type);
        Optional<Function> funcOptional = functions.stream()
                .filter(func -> func.getName().equals(funcName))
                .findFirst();
        if (!funcOptional.isPresent()) {
            logger.error("Unsupported function: {}", funcName);
            throw new IllegalArgumentException("Not support function: " + funcName);
        }
        Function function = funcOptional.get();
        List<Object> arguments = new ArrayList<>();

        while(!tokenValues.isEmpty()) {
            tokenValue = tokenValues.remove(0);
            if(tokenValue.getToken() == Token.JSON_PATH)
                arguments.add(context.read(tokenValue.getValue()));
            else if(tokenValue.getToken() == Token.FUNC)
                arguments.add(buildNodeFromFunction(null, tokenValues, context));
            else if(tokenValue.getToken() == Token.RAW)
                arguments.add(parseRawData(tokenValue.getValue(), context));
            else if(tokenValue.getToken() == Token.END_FUNC)
                break;
        }

        return function.invoke(type, arguments);
    }

    private Object parseRawData(String rawData, DocumentContext context) {
        if(rawData.contains(".")) {
            try {
                return new BigDecimal(rawData);
            }
            catch (NumberFormatException ex) {
                logger.trace("Cannot parse [{}] as decimal", rawData);
            }
        }
        else {
            try {
                return new BigInteger(rawData);
            }
            catch (NumberFormatException ex) {
                logger.trace("Cannot parse [{}] as integer", rawData);
            }
        }
        if(rawData.equalsIgnoreCase("true") || rawData.equalsIgnoreCase("false")) {
            return rawData.equalsIgnoreCase("true");
        }
        return buildStringFromRawData(rawData, context);
    }

    private Object buildNodeFromRawData(Type type, String rawData, DocumentContext context) {
        logger.trace("build Node with [{}] rawData to [{}] type", rawData, type);
        switch (type) {
            case STRING:
                return buildStringFromRawData(rawData, context);
            case INTEGER:
            case NUMBER:
            case BOOLEAN:
                return castToType(rawData, type);
            default:
                return context.configuration().jsonProvider().parse(rawData);
        }
    }

    private Object castToType(Object object, Type type) {
        switch (type) {
            case STRING:
                return object.toString();
            case INTEGER:
                if (object instanceof String || object instanceof Integer || object instanceof Long)
                    return Long.parseLong(object.toString());
                if (object instanceof Float)
                    return Math.round((float) object);
                if (object instanceof Double)
                    return Math.round((double) object);
                throw new IllegalArgumentException("Cannot cast " + object.getClass() + " to integer");
            case NUMBER:
                if (object instanceof String || object instanceof Integer || object instanceof Long || object instanceof Float || object instanceof Double)
                    return new BigDecimal(object.toString());
                throw new IllegalArgumentException("Cannot cast " + object.getClass() + " to number");
            case BOOLEAN:
                if (object instanceof Boolean)
                    return object;
                if (object instanceof Integer)
                    return !object.equals(0);
                if (object instanceof Long)
                    return !object.equals(0L);
                if (object instanceof Float)
                    return !object.equals(0F);
                if (object instanceof Double)
                    return !object.equals(0D);
                if (object instanceof String)
                    return ((String) object).equalsIgnoreCase("true");
                throw new IllegalArgumentException("Cannot cast " + object.getClass() + " to boolean");
        }
        return object;
    }

    private String buildStringFromRawData(String rawData, DocumentContext context) {
        Matcher matcher = Pattern.compile(PATTERN_INLINE_VARIABLE).matcher(rawData);
        int startIndex = 0;
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            int groupStart = matcher.start();
            int groupEnd = matcher.end();
            if (startIndex < groupStart) {
                builder.append(rawData, startIndex, groupStart);
            }
            builder.append(build(matcher.group(1), context));
            startIndex = groupEnd;
        }

        if (startIndex < rawData.length())
            builder.append(rawData, startIndex, rawData.length());

        return builder.toString();
    }

    private boolean isValidKey(String key) {
        return !KEY_ARRAY_PATH.equals(key);
    }

    public enum Type {
        STRING(null, "str", "string"),
        INTEGER(null, "int", "integer"),
        NUMBER(null, "num", "number"),
        BOOLEAN(null, "bool", "boolean"),
        OBJECT(null, "obj", "object"),
        STRING_ARRAY(Type.STRING, "str[]", "string[]"),
        INTEGER_ARRAY(Type.INTEGER, "int[]", "integer[]"),
        NUMBER_ARRAY(Type.NUMBER, "num[]", "number[]"),
        BOOLEAN_ARRAY(Type.BOOLEAN, "bool[]", "boolean[]"),
        OBJECT_ARRAY(Type.OBJECT, "obj[]", "object[]");

        public final boolean isArray;
        public final Type elementType;
        private final String[] values;

        Type(Type elementType, String... values) {
            this.isArray = elementType != null;
            this.elementType = elementType;
            this.values = values;
        }

        static Type from(String value) {
            return Stream.of(Type.values())
                    .filter(type -> Stream.of(type.values).anyMatch(v -> v.equalsIgnoreCase(value)))
                    .findFirst()
                    .orElse(null);
        }

    }

}
