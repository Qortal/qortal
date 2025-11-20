package org.qortal.network;

public class RNSCommon {

    /**
     * Destination application name
     */
    public static String MAINNET_APP_NAME = "qortal";      // production
    public static String TESTNET_APP_NAME = "qortaltest";  // test net

    /**
     * Configuration path relative to the Qortal launch directory
     */
    public static String defaultRNSConfigPath = ".reticulum";
    public static String defaultRNSConfigPathTestnet = ".reticulum_test";

    /**
     * Default config
     */
    public static String defaultRNSConfig = "reticulum_default_config.yml";
    public static String defaultRNSConfigTestnet = "reticulum_default_testnet_config.yml";

    /**
     * Reticulum port for TCP Client interfaces
     */
    public static Integer MAINNET_IF_TCP_PORT = 4242;
    public static Integer TESTNET_IF_TCP_PORT = 4240;

    /**
     * Reticulum Jinjava configuration template name
     */
    public static String jinjaConfigTemplateName = "reticulum_config_template.jinja";

    /**
     * Qortal Peer "aspect". For Reticulum, this translates to aspects:
     * BASE ~= "qortal.core"
     * DATA ~= "qortal.qdn"
     */
    public enum PeerAspect {
        BASE,
        DATA;
    }

    /**
     * Qortal Peer Type
     */
    public enum PeerType {
        IP,
        RETICULUM;
    }

}
