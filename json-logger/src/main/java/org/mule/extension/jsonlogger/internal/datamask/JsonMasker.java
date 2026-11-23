package org.mule.extension.jsonlogger.internal.datamask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;

import org.mule.extension.jsonlogger.api.pojos.MaskingStrategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

public class JsonMasker {

    private static final Pattern digits = Pattern.compile("\\d");
    private static final Pattern capitalLetters = Pattern.compile("[A-Z]");
    private static final Pattern nonSpecialCharacters = Pattern.compile("[^X\\s!-/:-@\\[-`{-~]");

    private static final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS).build();

    public static final String FULL_MASK = "****";

    private final Set<String> blacklistedKeys;
    private final Set<JsonPath> blacklistedJsonPaths;
    private final boolean enabled;
    private final MaskingStrategy strategy;

    public JsonMasker(Collection<String> blacklist, boolean enabled) {
        this(blacklist, enabled, MaskingStrategy.PRESERVE_FORMAT);
    }

    public JsonMasker(Collection<String> blacklist, boolean enabled, MaskingStrategy strategy) {
        this.enabled = enabled;
        this.strategy = (strategy == null) ? MaskingStrategy.PRESERVE_FORMAT : strategy;

        blacklistedKeys = new HashSet<>();
        blacklistedJsonPaths = new HashSet<>();

        blacklist.forEach(rawItem -> {
            String item = rawItem.trim();
            if (item.isEmpty()) {
                return;
            }
            if (item.startsWith("$")) {
                blacklistedJsonPaths.add(JsonPath.compile(item));
            } else {
                blacklistedKeys.add(item.toUpperCase());
            }
        });
    }

    public JsonMasker() {
        this(Collections.emptySet(), true);
    }

    public JsonMasker(boolean enabled) {
        this(Collections.emptySet(), enabled);
    }

    public JsonMasker(Collection<String> blacklist) {
        this(blacklist, true);
    }

    public boolean hasRules() {
        return !blacklistedKeys.isEmpty() || !blacklistedJsonPaths.isEmpty();
    }

    public JsonNode mask(JsonNode target) {
        if (!enabled || !hasRules())
            return target;
        if (target == null)
            return null;

        Set<String> expandedBlacklistedPaths = new HashSet<>();
        for (JsonPath jsonPath : blacklistedJsonPaths) {
            if (jsonPath.isDefinite()) {
                expandedBlacklistedPaths.add(jsonPath.getPath());
            } else {
                for (JsonNode node : jsonPath.<ArrayNode>read(target, jsonPathConfig)) {
                    expandedBlacklistedPaths.add(node.asText());
                }
            }
        }

        return traverseAndMask(target.deepCopy(), expandedBlacklistedPaths, "$", false);
    }

    @SuppressWarnings("ConstantConditions")
    private JsonNode traverseAndMask(JsonNode target, Set<String> expandedBlacklistedPaths, String path, Boolean isBlackListed) {
        if ((target.isTextual() || target.isNumber() || target.isBoolean()) && isBlackListed) {
            return new TextNode(maskValue(target));
        }

        if (target.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = target.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = appendPath(path, field.getKey());
                if (blacklistedKeys.contains(field.getKey().toUpperCase()) || expandedBlacklistedPaths.contains(childPath) || isBlackListed == true) {
                    ((ObjectNode) target).replace(field.getKey(), traverseAndMask(field.getValue(), expandedBlacklistedPaths, childPath, true));
                } else {
                    ((ObjectNode) target).replace(field.getKey(), traverseAndMask(field.getValue(), expandedBlacklistedPaths, childPath, false));
                }
            }
        }
        if (target.isArray()) {
            for (int i = 0; i < target.size(); i++) {
                String childPath = appendPath(path, i);
                if (expandedBlacklistedPaths.contains(childPath) || isBlackListed == true) {
                    ((ArrayNode) target).set(i, traverseAndMask(target.get(i), expandedBlacklistedPaths, childPath, true));
                } else {
                    ((ArrayNode) target).set(i, traverseAndMask(target.get(i), expandedBlacklistedPaths, childPath, false));
                }
            }
        }
        return target;
    }

    private static String appendPath(String path, String key) {
        return path + "['" + key + "']";
    }

    private static String appendPath(String path, int ind) {
        return path + "[" + ind + "]";
    }

    private String maskValue(JsonNode value) {
        switch (strategy) {
            case FULL:
                return FULL_MASK;
            case HASH:
                return "sha256:" + sha256(value.asText()).substring(0, 16);
            case PRESERVE_FORMAT:
            default:
                return value.isNumber() ? maskNumber(value.asText()) : maskString(value.asText());
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String maskString(String value) {
        String tmpMasked = digits.matcher(value).replaceAll("*");
        tmpMasked = capitalLetters.matcher(tmpMasked).replaceAll("X");
        return nonSpecialCharacters.matcher(tmpMasked).replaceAll("x");
    }

    private static String maskNumber(String value) {
        return value.replaceAll("[0-9]", "*");
    }

}