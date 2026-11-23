package org.mule.extension.jsonlogger.internal.datamask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts sensitive data (PII, secrets) found anywhere inside string values, using built-in or custom regexes.
 */
public class PatternRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Map<String, String> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put("EMAIL", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        BUILT_INS.put("CREDIT_CARD", "\\b(?:\\d{4}[ -]?){3}\\d{1,4}\\b");
        BUILT_INS.put("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b");
        BUILT_INS.put("JWT", "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
        BUILT_INS.put("BEARER_TOKEN", "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
        BUILT_INS.put("IPV4", "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    }

    private final List<Pattern> patterns;

    public PatternRedactor(Collection<String> definitions) {
        List<Pattern> compiled = new ArrayList<>();
        for (String def : definitions) {
            String d = def.trim();
            if (d.isEmpty()) {
                continue;
            }
            String regex = BUILT_INS.getOrDefault(d.toUpperCase(Locale.ROOT), d);
            compiled.add(Pattern.compile(regex));
        }
        this.patterns = Collections.unmodifiableList(compiled);
    }

    public static PatternRedactor fromCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return null;
        }
        PatternRedactor r = new PatternRedactor(Arrays.asList(csv.split(",")));
        return r.patterns.isEmpty() ? null : r;
    }

    public static Set<String> builtIns() {
        return BUILT_INS.keySet();
    }

    public String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(result);
            if (m.find()) {
                result = m.replaceAll(REDACTED);
            }
        }
        return result;
    }

    /**
     * Redacts all string values in place, skipping the given top-level field names.
     */
    public void redactInPlace(ObjectNode root, Set<String> skipTopLevelFields) {
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        it.forEachRemaining(entries::add);
        for (Map.Entry<String, JsonNode> e : entries) {
            if (!skipTopLevelFields.contains(e.getKey())) {
                root.set(e.getKey(), redactNode(e.getValue()));
            }
        }
    }

    public JsonNode redactNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            String original = node.asText();
            String redacted = redact(original);
            return original.equals(redacted) ? node : new TextNode(redacted);
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                obj.set(n, redactNode(obj.get(n)));
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, redactNode(arr.get(i)));
            }
            return arr;
        }
        return node;
    }
}
