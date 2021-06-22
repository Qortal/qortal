package org.qortal.network;

public interface Task {
	String getName();
    void perform() throws InterruptedException;
}