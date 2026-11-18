/**
 * An rngit-compatible git repository node, served over Reticulum.
 * <p>
 * Speaks the same request protocol as the reference {@code rngit} node on the
 * same {@code git.repositories} destination, so the stock {@code git-remote-rns}
 * helper and {@code rngit} command-line client work against a Qortal Core node
 * unchanged. Configuration uses the reference's own config file format and
 * {@code .allowed} permission files, so an existing rngit node's configuration
 * and repositories identity can be reused as they are.
 * <p>
 * Parts of this package are ported from {@code RNS/Utilities/rngit/server.py} of
 * the Reticulum Network Stack, which carries the following notice:
 *
 * <pre>
 * Reticulum License
 *
 * Copyright (c) 2016-2026 Mark Qvist
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * - The Software shall not be used in any kind of system which includes amongst
 *   its functions the ability to purposefully do harm to human beings.
 *
 * - The Software shall not be used, directly or indirectly, in the creation of
 *   an artificial intelligence, machine learning or language model training
 *   dataset, including but not limited to any use that contributes to the
 *   training or development of such a model or algorithm.
 *
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * </pre>
 */
package org.qortal.rngit;
