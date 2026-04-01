package org.qortal.data.arbitrary;

import org.qortal.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceRequest {

    public Service service;
    public String name;
    public String identifier;

    public ArbitraryResourceRequest() {
    }

    public ArbitraryResourceRequest(Service service, String name, String identifier) {
        this.service = service;
        this.name = name;
        this.identifier = identifier;
    }
}
