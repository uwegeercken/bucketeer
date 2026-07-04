package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;

@Component
@SessionScope
public class SessionContext implements Serializable {

    private String selectedServer;

    public String getSelectedServer() {
        return selectedServer;
    }

    public void setSelectedServer(String selectedServer) {
        this.selectedServer = selectedServer;
    }
}
