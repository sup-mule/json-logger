package org.mule.extension.jsonlogger.internal;

import org.mule.extension.jsonlogger.api.pojos.LoggerConfig;
import org.mule.extension.jsonlogger.internal.destinations.Destination;
import org.mule.extension.jsonlogger.internal.singleton.ConfigsSingleton;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.RefName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;

import org.mule.extension.jsonlogger.internal.datamask.JsonMasker;
import org.mule.extension.jsonlogger.internal.datamask.PatternRedactor;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class represents an extension configuration, values set in this class are commonly used across multiple
 * operations since they represent something core from the extension.
 */
@Operations(JsonloggerOperations.class)
public class JsonloggerConfiguration extends LoggerConfig implements Initialisable, Disposable {

    @Inject
    ConfigsSingleton configsSingleton;

    @RefName
    private String configName;

    @Parameter
    @Optional
    @Placement(tab = "Destinations")
    private Destination externalDestination;

    public String getConfigName() {
        return configName;
    }

    /** Timer methods for Elapsed Time **/

    public ConcurrentHashMap<String,Long> timers = new ConcurrentHashMap<String,Long>();

    public ConcurrentHashMap<String, Long> getTimers() { return timers; }

    public void setTimers(ConcurrentHashMap<String, Long> timers) { this.timers = timers; }

    public Long getCachedTimerTimestamp(String key, Long initialTimeStamp) throws Exception {
        Long startTimestamp = timers.putIfAbsent(key, initialTimeStamp);
        return (startTimestamp == null) ? timers.get(key) : startTimestamp;
    }

    public void removeCachedTimerTimestamp(String key) {
        timers.remove(key);
    }

    /** Init configs singleton **/
    public void setExternalDestination(Destination externalDestination) {
        this.externalDestination = externalDestination;
    }

    public Destination getExternalDestination() {
        return externalDestination;
    }

    /** Pre-computed (static) output settings, built once at initialisation instead of on every log call **/

    private transient List<String> disabledFieldsList = Collections.emptyList();
    private transient JsonMasker contentMasker;
    private transient PatternRedactor redactor;

    public List<String> getDisabledFieldsList() {
        return disabledFieldsList;
    }

    /** @return masker for JSON content fields, or null when no masking rules are configured */
    public JsonMasker getContentMasker() {
        return contentMasker;
    }

    /** @return redactor applied to all string values in the log entry, or null when not configured */
    public PatternRedactor getRedactor() {
        return redactor;
    }

    static List<String> csvToList(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public void initialise() throws InitialisationException {
        if (getJsonOutput() != null) {
            this.disabledFieldsList = csvToList(getJsonOutput().getDisabledFields());
            List<String> maskingFields = csvToList(getJsonOutput().getContentFieldsDataMasking());
            this.contentMasker = maskingFields.isEmpty() ? null : new JsonMasker(maskingFields, true, getJsonOutput().getMaskingStrategy());
            try {
                this.redactor = PatternRedactor.fromCsv(getJsonOutput().getRedactionPatterns());
            } catch (Exception e) {
                throw new InitialisationException(
                        org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage("Invalid redaction pattern in 'Redaction patterns': " + e.getMessage()), e, this);
            }
        }
        if (this.externalDestination != null) {
            this.externalDestination.initialise();
        }
        configsSingleton.addConfig(configName, this); // Should be refactored once SDK supports passing configs to Scopes
    }

    @Override
    public void dispose() {
        if (this.externalDestination != null) {
            this.externalDestination.dispose();
        }
    }
}
