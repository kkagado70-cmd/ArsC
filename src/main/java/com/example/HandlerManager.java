package com.arsenal.client.handlers;
import com.arsenal.client.event.EventManager;
import com.arsenal.client.handlers.impl.*;
import com.arsenal.client.handlers.impl.ModuleBootstrapHandler;

import java.util.ArrayList;

public class HandlerManager {
    private final ArrayList<Handler> handlers = new ArrayList<>();
    
    // "+auth-related"
    public void initialize() {
        
        handlers.add(new KeyHandler());
        handlers.add(new HurtTickHandler());
        handlers.add(new SprintController());
        handlers.add(new SwapStateManager());
        handlers.add(new CommandHandler());
        handlers.add(new BroadcastHandler());
        handlers.add(new ModuleBootstrapHandler());

        for (Handler handler : handlers) {
            EventManager.register(handler);
        }
    }

    public void shutdown() {
        for (Handler handler : handlers) {
            EventManager.unregister(handler);
        }
        SwapStateManager.clear();
        handlers.clear();
    }
}

