# Qortal and Reticulum

This directory contains tools, examples and testing setup(s) for Reticulum feature implementations and their integration into Qortal.

## Reticulum Network Stack

The base of reticulum integration in Qortal is the Reticulum Network Stack which provides:

* A native Java Reticulum implementation that stays as closely compatible to the reference Python RNS as possible
* 2nd network stack independent of TCP/IP stack, serving base and data network trafic for all Qortal Message types between Qortal Core nodes.
* The basis for further Qortal features running over Reticulum

## rngit

The first Reticulum native feature in Qortal is serving Git repositories over Reticulum. It includes:

* A port of the Python RNS utility *rngit* including the *pages* service serving Git work documents to the *Nomad Network* (completes support of rngit "composable primitives").
* A Java native implementation of the *rngit* and *pages* service.
* Git repos in Qortal are stored on QDN instead of the original single-node file system.
* Use of the Qortal SERVICE *GIT_REPOSITORY*.
* The Qortal API */git* endpoint providing access to Git repos to Qortal Q-Apps. A sample *rngit* QAPP can be found in this directory.
* Turning Qortal Core into a *rngit* node is done by defining the Qortal setting "rngitEnabled: true".
* The directory containing the *rngit* configuration for the node can be configured with the Qortal setting "rngitConfigPath" (default: "rngit")
* A Qortal Core node with Git enabled is fully compatible with the reference *rngit* CLI utility. The Python RNS *rngit* may be used as CLI client.
