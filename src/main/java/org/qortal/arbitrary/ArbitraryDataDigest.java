package org.qortal.arbitrary;

import org.qortal.repository.DataException;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ArbitraryDataDigest {

    private final Path path;
    private byte[] hash;

    public ArbitraryDataDigest(Path path) {
        this.path = path;
    }

    public void compute() throws IOException, DataException {
        List<Path> allPaths = Files.walk(path).filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        Path basePathAbsolute = this.path.toAbsolutePath();

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new DataException("SHA-256 hashing algorithm unavailable");
        }

        for (Path path : allPaths) {
            // We need to work with paths relative to the base path, to ensure the same hash
            // is generated on different systems
            Path relativePath = basePathAbsolute.relativize(path.toAbsolutePath());

            // Exclude Qortal folder since it can be different each time
            // We only care about hashing the actual user data
            if (relativePath.startsWith(".qortal/")) {
                continue;
            }

            // Account for \ VS / : Linux VS Windows
            String pathString = relativePath.toString();

            if(relativePath.getFileSystem().toString().contains("Windows")) {
                pathString = pathString.replace("\\","/");
            }

            // Hash path
            byte[] filePathBytes = pathString.getBytes(StandardCharsets.UTF_8);
            System.out.printf("Path: %s \n", pathString);
            System.out.printf("Path Byte Array: %s \n", Arrays.toString(filePathBytes));
            sha256.update(filePathBytes);

            // Hash contents
            byte[] fileContent = Files.readAllBytes(path);
            System.out.printf("File Content: %s \n", Arrays.toString(fileContent));
            sha256.update(fileContent);
        }
        this.hash = sha256.digest();
    }

    public boolean isHashValid(byte[] hash) {
        return Arrays.equals(hash, this.hash);
    }

    public byte[] getHash() {
        return this.hash;
    }

    public String getHash58() {
        if (this.hash == null) {
            return null;
        }
        return Base58.encode(this.hash);
    }

}
