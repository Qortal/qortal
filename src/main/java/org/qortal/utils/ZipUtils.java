/*
 * MIT License
 *
 * Copyright (c) 2017 Eugen Paraschiv
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Code modified in 2021 for Qortal Core
 *
 */

package org.qortal.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

import org.qortal.controller.Controller;
import org.qortal.crypto.AES;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.NoSuchPaddingException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    public static void zip(String sourcePath, String destFilePath, String enclosingFolderName) throws IOException, InterruptedException {
        File sourceFile = new File(sourcePath);
        boolean isSingleFile = Paths.get(sourcePath).toFile().isFile();
        FileOutputStream fileOutputStream = new FileOutputStream(destFilePath);
        
        // 🔧 Use best speed compression level
        ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream);        
        ZipUtils.zip(sourceFile, enclosingFolderName, zipOutputStream, isSingleFile);
        
        zipOutputStream.close();
        fileOutputStream.close();
    }
    

    public static void zip(final File fileToZip, final String enclosingFolderName, final ZipOutputStream zipOut, boolean isSingleFile) throws IOException, InterruptedException {
        if (Controller.isStopping()) {
            throw new InterruptedException("Controller is stopping");
        }

        // Handle single file resources slightly differently
        if (isSingleFile) {
            // Create enclosing folder
            zipOut.putNextEntry(new ZipEntry(enclosingFolderName + "/"));
            zipOut.closeEntry();
            // Place the supplied file within the folder
            ZipUtils.zip(fileToZip, enclosingFolderName + "/" + fileToZip.getName(), zipOut, false);
            return;
        }

        if (fileToZip.isDirectory()) {
            if (enclosingFolderName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(enclosingFolderName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(enclosingFolderName + "/"));
                zipOut.closeEntry();
            }
            final File[] children = fileToZip.listFiles();
            for (final File childFile : children) {
                ZipUtils.zip(childFile, enclosingFolderName + "/" + childFile.getName(), zipOut, false);
            }
            return;
        }
        final FileInputStream fis = new FileInputStream(fileToZip);
        final ZipEntry zipEntry = new ZipEntry(enclosingFolderName);
        zipOut.putNextEntry(zipEntry);
        final byte[] bytes = new byte[65536];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    /**
     * Unzips a file from the given source path to the destination path.
     * 
     * @param sourcePath Path to the ZIP file
     * @param destPath Destination directory for extracted files
     * @throws IOException If extraction fails
     */
    public static void unzip(String sourcePath, String destPath) throws IOException {
        final File destDir = new File(destPath);
        // Buffer size: 512KB - optimized for large files (reduces syscalls)
        final int BUFFER_SIZE = 512 * 1024;
        final byte[] buffer = new byte[BUFFER_SIZE];
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(sourcePath), BUFFER_SIZE);
             ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                final File newFile = ZipUtils.newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }
    
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(newFile), BUFFER_SIZE)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * Unzips from an InputStream directly to the destination path.
     * This is useful for streaming operations where the ZIP data comes from a stream.
     * 
     * @param zipInputStream The ZipInputStream to read from
     * @param destPath Destination directory for extracted files
     * @throws IOException If extraction fails
     */
    public static void unzipFromStream(ZipInputStream zipInputStream, String destPath) throws IOException {
        final File destDir = new File(destPath);
        // Buffer size: 512KB - optimized for large files
        final int BUFFER_SIZE = 512 * 1024;
        final byte[] buffer = new byte[BUFFER_SIZE];
        
        ZipEntry zipEntry = zipInputStream.getNextEntry();
        while (zipEntry != null) {
            final File newFile = ZipUtils.newFile(destDir, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(newFile), BUFFER_SIZE)) {
                    int len;
                    while ((len = zipInputStream.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                }
            }
            zipEntry = zipInputStream.getNextEntry();
        }
        zipInputStream.closeEntry();
    }

    /**
     * Decrypts and unzips an encrypted ZIP file in a single streaming pass.
     * This eliminates the need for an intermediate decrypted file, significantly
     * improving performance by reducing disk I/O.
     * 
     * @param algorithm The encryption algorithm (e.g., "AES/CBC/PKCS5Padding")
     * @param key The secret key for decryption
     * @param encryptedFilePath Path to the encrypted ZIP file
     * @param destPath Destination directory for extracted files
     * @throws IOException If decryption or extraction fails
     * @throws NoSuchPaddingException If the padding scheme is not available
     * @throws NoSuchAlgorithmException If the algorithm is not available
     * @throws InvalidAlgorithmParameterException If the IV is invalid
     * @throws InvalidKeyException If the key is invalid
     */
    public static void decryptAndUnzip(String algorithm, SecretKey key, String encryptedFilePath, 
            String destPath) throws IOException, NoSuchPaddingException, NoSuchAlgorithmException, 
            InvalidAlgorithmParameterException, InvalidKeyException {
        
        // Buffer size: 256KB - good balance between performance and memory for all machines
        // Matches AES.decryptFile() for consistency. 512KB provides marginal performance gain
        // but uses 2x memory, which is significant with 5 concurrent builds (1.25MB vs 2.5MB)
        final int BUFFER_SIZE = 256 * 1024;
        
        try (BufferedInputStream encryptedStream = new BufferedInputStream(
                new FileInputStream(encryptedFilePath), BUFFER_SIZE);
             javax.crypto.CipherInputStream decryptingStream = AES.createDecryptingInputStream(
                algorithm, key, encryptedStream);
             ZipInputStream zipStream = new ZipInputStream(decryptingStream)) {
            
            unzipFromStream(zipStream, destPath);
        }
    }
    

    /**
     * See: https://snyk.io/research/zip-slip-vulnerability
     */
    public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

}
