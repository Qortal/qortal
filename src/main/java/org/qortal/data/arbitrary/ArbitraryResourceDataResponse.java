package org.qortal.data.arbitrary;

import org.qortal.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceDataResponse {

    public Service service;
    public String name;
    public String identifier;

    /** Base64-encoded raw data. Null when an error occurred. */
    public String data;

    /** Human-readable error message. Null on success. */
    public String error;

    public ArbitraryResourceDataResponse() {
    }

    private ArbitraryResourceDataResponse(Service service, String name, String identifier) {
        this.service = service;
        this.name = name;
        this.identifier = identifier;
    }

    public static ArbitraryResourceDataResponse success(Service service, String name, String identifier, String base64Data) {
        ArbitraryResourceDataResponse r = new ArbitraryResourceDataResponse(service, name, identifier);
        r.data = base64Data;
        return r;
    }

    public static ArbitraryResourceDataResponse error(Service service, String name, String identifier, String errorMessage) {
        ArbitraryResourceDataResponse r = new ArbitraryResourceDataResponse(service, name, identifier);
        r.error = errorMessage;
        return r;
    }
}
