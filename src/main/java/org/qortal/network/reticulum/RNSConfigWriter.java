package org.qortal.network.reticulum;

import com.google.common.collect.Maps;
import com.hubspot.jinjava.Jinjava;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qortal.settings.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.reticulum.constant.ReticulumConstant.CONFIG_FILE_NAME;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Generates the Reticulum {@code config.yml} from the packaged Jinjava template, falling back to a
 * packaged default config if rendering fails.
 * <p>
 * Runs once, from the RNS constructor, before the Reticulum stack is built. Written only when the
 * file is missing or {@code reticulumRegenerateConfigOnRestart} is set, so operator edits survive
 * restarts by default.
 */
@Slf4j
final class RNSConfigWriter {

    private RNSConfigWriter() {
    }

    /**
     * Ensure {@code configDir} holds a usable Reticulum config.
     *
     * @param configDir   config directory, created when absent
     * @param appName     network name fallback when {@code reticulumNetworkName} is empty
     * @param targetPort  port for the TCPServer/BackboneServer interface
     */
    static void ensureConfig(String configDir, String appName, int targetPort) throws IOException {
        File dir = new File(configDir);
        if (!dir.exists()) {
            dir.mkdir();
        }
        Path configFile = Path.of(dir.getAbsolutePath()).resolve(CONFIG_FILE_NAME);

        // Report which of the three branches was taken, at INFO: this runs once per start, and
        // "we left your edits alone" is the case an operator most needs to be able to confirm.
        boolean exists = Files.exists(configFile);
        if (exists && !Settings.getInstance().isReticulumRegenerateConfigOnRestart()) {
            log.info("Reticulum config exists at {} — leaving it as-is", configFile);
            return;
        }
        if (exists) {
            log.info("Regenerating Reticulum config at {} (reticulumRegenerateConfigOnRestart is set)", configFile);
        } else {
            log.info("Writing new Reticulum config to {}", configFile);
        }

        try {
            render(configFile, appName, targetPort);
        } catch (Exception e) {
            log.error("Failed to render config file - creating fallback default  config file", e);
            copyPackagedDefault(configFile);
        }
    }

    private static void render(Path configFile, String appName, int targetPort) throws IOException {
        String fqdn = InetAddress.getLocalHost().getCanonicalHostName();

        // A node must not list itself as a gateway to dial.
        List<String> tcpGatewayServers =
                Arrays.stream(Settings.getInstance().getReticulumTcpGatewayServers()).collect(Collectors.toList());
        List<String> backboneGatewayServers =
                Arrays.stream(Settings.getInstance().getReticulumBackboneGatewayServers()).collect(Collectors.toList());
        tcpGatewayServers.remove(fqdn);
        backboneGatewayServers.remove(fqdn);

        // jinjava variables set in context:
        // * tcp_gateway_servers: list of nodes with a TCPServerInterface
        // * backbone_gateway_servers: list of nodes with a BackboneServerInterface
        // * num_client_interfaces: number of client interfaces to gateways be configured
        // * host_fqdn: host FQDN
        // * qortal_network_name: either "qortal" or "qortaltest" (from isTestnet)
        // * is_reticulum_gateway: one of the instances (Qortal core or RNS) has
        //                         at least one Gateway interface
        // * target_port: target port for TCPServerInterface or BackboneServerInterface (only)
        // * use_python_rns: use local shared python rnsd (has to provide a gateway interface)
        // * python_rns_if_port: rnsd TCPServerInterface port (if rnsd gateway is a TCPServerInterface)
        String tcpGateways = StringUtils.join(tcpGatewayServers, " ");
        String backboneGateways = StringUtils.join(backboneGatewayServers, " ");
        log.info("reticulumTcpGateways: {}, reticulumBackboneGateways: {}", tcpGateways, backboneGateways);

        String networkName = Settings.getInstance().getReticulumNetworkName();
        Map<String, Object> context = Maps.newHashMap();
        context.put("tcp_gateway_servers", tcpGateways);
        context.put("backbone_gateway_servers", backboneGateways);
        context.put("num_client_interfaces", Settings.getInstance().getReticulumDesiredClientInterfaces());
        context.put("host_fqdn", fqdn);
        context.put("qortal_network_name", networkName.isEmpty() ? appName : networkName);
        context.put("target_port", targetPort);
        context.put("is_reticulum_gateway", Settings.getInstance().getReticulumIsGateway() ? "true" : "false");
        context.put("use_python_rns", Settings.getInstance().getReticulumUsePythonRNS() ? "true" : "false");
        context.put("python_rns_if_port", Settings.getInstance().getReticulumPythonRNSGatewayPort());
        context.put("passphrase", Settings.getInstance().getReticulumPassphrase());

        log.info("Rendering new Reticulum configuration file from resource {}", RNSCommon.jinjaConfigTemplateName);
        InputStream templateStream = RNSConfigWriter.class.getClassLoader()
                .getResourceAsStream(RNSCommon.jinjaConfigTemplateName);
        String template = new BufferedReader(new InputStreamReader(templateStream))
                .lines().parallel().collect(Collectors.joining("\n"));
        String renderedConfig = new Jinjava().render(template, context);

        // Delete any existing config first. Files.write(CREATE, WRITE) does NOT truncate, so
        // regenerating a SHORTER config (e.g. after lowering reticulumDesiredClientInterfaces)
        // left the old file's trailing bytes in place — a stale/duplicated interface, and
        // sometimes a corrupt tail. Deleting guarantees the rendered file is exactly the new
        // content. (The fallback path below already uses Files.copy REPLACE_EXISTING.)
        Files.deleteIfExists(configFile);
        Files.write(configFile, renderedConfig.getBytes(), CREATE, WRITE);
    }

    private static void copyPackagedDefault(Path configFile) throws IOException {
        String resource = Settings.getInstance().isTestNet()
                ? RNSCommon.defaultRNSConfigTestnet : RNSCommon.defaultRNSConfig;
        InputStream defaultConfig = RNSConfigWriter.class.getClassLoader().getResourceAsStream(resource);
        Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
