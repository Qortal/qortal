package com.rust.litewalletjni;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertTrue;

public class LiteWalletJniTests {

    @Test
    public void everyNativeEntrypointIsProcessSerialized() {
        for (Method method : LiteWalletJni.class.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) {
                assertTrue(method.getName() + " must synchronize access to the process-global JNI context",
                        Modifier.isStatic(method.getModifiers()) && Modifier.isSynchronized(method.getModifiers()));
            }
        }
    }
}
