package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.Bootstrap;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.Triple;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;

public class HSQLDBImportExport {

    private static final Logger LOGGER = LogManager.getLogger(Bootstrap.class);

    public static void backupTradeBotStates(Repository repository, List<TradeBotData> additional) throws DataException {
        HSQLDBImportExport.backupCurrentTradeBotStates(repository, additional);
        HSQLDBImportExport.backupArchivedTradeBotStates(repository, additional);

        LOGGER.info("Exported sensitive/node-local data: trade bot states");
    }

    public static void backupMintingAccounts(Repository repository) throws DataException {
        HSQLDBImportExport.backupCurrentMintingAccounts(repository);

        LOGGER.info("Exported sensitive/node-local data: minting accounts");
    }


    /* Trade bot states */

    /**
     * Backs up the trade bot states currently in the repository, without combining them with past ones
     * @param repository
     * @param additional - any optional extra trade bot states to include in the backup
     * @throws DataException
     */
    private static void backupCurrentTradeBotStates(Repository repository, List<TradeBotData> additional) throws DataException {
        try {
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(true);

            // Load current trade bot data
            List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();


            // Add any additional entries if specified
            if (additional != null && !additional.isEmpty()) {
                allTradeBotData.addAll(additional);
            }

            // Convert them to JSON objects
            JSONArray currentTradeBotDataJson = new JSONArray();
            for (TradeBotData tradeBotData : allTradeBotData) {
                JSONObject tradeBotDataJson = tradeBotData.toJson();
                currentTradeBotDataJson.put(tradeBotDataJson);
            }

            // Wrap current trade bot data in an object to indicate the type
            JSONObject currentTradeBotDataJsonWrapper = new JSONObject();
            currentTradeBotDataJsonWrapper.put("type", "tradeBotStates");
            currentTradeBotDataJsonWrapper.put("dataset", "current");
            currentTradeBotDataJsonWrapper.put("data", currentTradeBotDataJson);

            // Write current trade bot data (just the ones currently in the database)
            String fileName = Paths.get(backupDirectory.toString(), "TradeBotStates.json").toString();
            FileWriter writer = new FileWriter(fileName);
            writer.write(currentTradeBotDataJsonWrapper.toString(2));
            writer.close();

        } catch (DataException | IOException e) {
            throw new DataException("Unable to export trade bot states from repository");
        }
    }

    /**
     * Backs up the trade bot states currently in the repository to a separate "archive" file,
     * making sure to combine them with any unique states already present in the archive.
     * @param repository
     * @param additional - any optional extra trade bot states to include in the backup
     * @throws DataException
     */
    private static void backupArchivedTradeBotStates(Repository repository, List<TradeBotData> additional) throws DataException {
        try {
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(true);

            // Load current trade bot data
            List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

            // Add any additional entries if specified
            if (additional != null && !additional.isEmpty()) {
                allTradeBotData.addAll(additional);
            }

            // Convert them to JSON objects
            JSONArray allTradeBotDataJson = new JSONArray();
            for (TradeBotData tradeBotData : allTradeBotData) {
                JSONObject tradeBotDataJson = tradeBotData.toJson();
                allTradeBotDataJson.put(tradeBotDataJson);
            }

            // We need to combine existing archived TradeBotStates data before overwriting
            String fileName = Paths.get(backupDirectory.toString(), "TradeBotStatesArchive.json").toString();
            File tradeBotStatesBackupFile = new File(fileName);
            if (tradeBotStatesBackupFile.exists()) {

                String jsonString = new String(Files.readAllBytes(Paths.get(fileName)));
                Triple<String, String, JSONArray> parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);
                if (parsedJSON.getA() == null || parsedJSON.getC() == null) {
                    throw new DataException("Missing data when exporting archived trade bot states");
                }
                String type = parsedJSON.getA();
                String dataset = parsedJSON.getB();
                JSONArray data = parsedJSON.getC();

                if (!type.equals("tradeBotStates") || !dataset.equals("archive")) {
                    throw new DataException("Format mismatch when exporting archived trade bot states");
                }

                Iterator<Object> iterator = data.iterator();
                while(iterator.hasNext()) {
                    JSONObject existingTradeBotDataItem = (JSONObject)iterator.next();
                    String existingTradePrivateKey = (String) existingTradeBotDataItem.get("tradePrivateKey");
                    // Check if we already have an entry for this trade
                    boolean found = allTradeBotData.stream().anyMatch(tradeBotData -> Base58.encode(tradeBotData.getTradePrivateKey()).equals(existingTradePrivateKey));
                    if (!found)
                        // Add the data from the backup file to our "allTradeBotDataJson" array as it's not currently in the db
                        allTradeBotDataJson.put(existingTradeBotDataItem);
                }
            }

            // Wrap all trade bot data in an object to indicate the type
            JSONObject allTradeBotDataJsonWrapper = new JSONObject();
            allTradeBotDataJsonWrapper.put("type", "tradeBotStates");
            allTradeBotDataJsonWrapper.put("dataset", "archive");
            allTradeBotDataJsonWrapper.put("data", allTradeBotDataJson);

            // Write ALL trade bot data to archive (current plus states that are no longer in the database)
            FileWriter  writer = new FileWriter(fileName);
            writer.write(allTradeBotDataJsonWrapper.toString(2));
            writer.close();

        } catch (DataException | IOException e) {
            throw new DataException("Unable to export trade bot states from repository");
        }
    }


    /* Minting accounts */

    /**
     * Backs up the minting accounts currently in the repository, without combining them with past ones
     * @param repository
     * @throws DataException
     */
    private static void backupCurrentMintingAccounts(Repository repository) throws DataException {
        try {
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(true);

            // Load current trade bot data
            List<MintingAccountData> allMintingAccountData = repository.getAccountRepository().getMintingAccounts();
            JSONArray currentMintingAccountJson = new JSONArray();
            for (MintingAccountData mintingAccountData : allMintingAccountData) {
                JSONObject mintingAccountDataJson = mintingAccountData.toJson();
                currentMintingAccountJson.put(mintingAccountDataJson);
            }

            // Wrap current trade bot data in an object to indicate the type
            JSONObject currentMintingAccountDataJsonWrapper = new JSONObject();
            currentMintingAccountDataJsonWrapper.put("type", "mintingAccounts");
            currentMintingAccountDataJsonWrapper.put("dataset", "current");
            currentMintingAccountDataJsonWrapper.put("data", currentMintingAccountJson);

            // Write current trade bot data (just the ones currently in the database)
            String fileName = Paths.get(backupDirectory.toString(), "MintingAccounts.json").toString();
            FileWriter writer = new FileWriter(fileName);
            writer.write(currentMintingAccountDataJsonWrapper.toString(2));
            writer.close();

        } catch (DataException | IOException e) {
            throw new DataException("Unable to export minting accounts from repository");
        }
    }


    /* Utils */

    /**
     * Imports data from supplied file
     * Data type is loaded from the file itself, and if missing, TradeBotStates is assumed
     *
     * @param filename
     * @param repository
     * @throws DataException
     * @throws IOException
     */
    public static void importDataFromFile(String filename, Repository repository) throws DataException, IOException {
        importDataFromFile(filename, repository, false);
    }

    public static void reconcileDataFromFile(String filename, Repository repository) throws DataException, IOException {
        importDataFromFile(filename, repository, true);
    }

    private static void importDataFromFile(String filename, Repository repository, boolean reconcile) throws DataException, IOException {
        Path path = Paths.get(filename);
        if (!path.toFile().exists()) {
            throw new FileNotFoundException(String.format("File doesn't exist: %s", filename));
        }
        byte[] fileContents = Files.readAllBytes(path);
        if (fileContents == null) {
            throw new FileNotFoundException(String.format("Unable to read file contents: %s", filename));
        }

        LOGGER.info(String.format("Importing %s into repository ...", filename));

        String jsonString = new String(fileContents);
        Triple<String, String, JSONArray> parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);
        if (parsedJSON.getA() == null || parsedJSON.getC() == null) {
            throw new DataException(String.format("Missing data when importing %s into repository", filename));
        }
        String type = parsedJSON.getA();
        JSONArray data = parsedJSON.getC();

        Iterator<Object> iterator = data.iterator();
        while(iterator.hasNext()) {
            JSONObject dataJsonObject = (JSONObject)iterator.next();

            if (type.equals("tradeBotStates")) {
                if (reconcile) {
                    byte[] key = Base58.decode(dataJsonObject.getString("tradePrivateKey"));
                    if (repository.getCrossChainRepository().getTradeBotData(key) != null
                            || repository.getCrossChainRepository().wasTradeBotDeleted(key)) continue;
                }
                HSQLDBImportExport.importTradeBotDataJSON(dataJsonObject, repository);
            }
            else if (type.equals("mintingAccounts")) {
                HSQLDBImportExport.importMintingAccountDataJSON(dataJsonObject, repository);
            }
            else {
                throw new DataException(String.format("Unrecognized data type when importing %s into repository", filename));
            }

        }
        LOGGER.info(String.format("Imported %s into repository from %s", type, filename));
    }

    private static void importTradeBotDataJSON(JSONObject tradeBotDataJson, Repository repository) throws DataException {
        TradeBotData tradeBotData = TradeBotData.fromJson(tradeBotDataJson);
        repository.getCrossChainRepository().save(tradeBotData);
    }

    private static void importMintingAccountDataJSON(JSONObject mintingAccountDataJson, Repository repository) throws DataException {
        MintingAccountData mintingAccountData = MintingAccountData.fromJson(mintingAccountDataJson);
        repository.getAccountRepository().save(mintingAccountData);
    }

    public static Path getExportDirectory(boolean createIfNotExists) throws DataException {
        Path backupPath = Paths.get(Settings.getInstance().getExportPath());

        if (createIfNotExists) {
            // Create the qortal-backup folder if it doesn't exist
            try {
                Files.createDirectories(backupPath);
            } catch (IOException e) {
                LOGGER.info(String.format("Unable to create %s folder", backupPath.toString()));
                throw new DataException(String.format("Unable to create %s folder", backupPath.toString()));
            }
        }

        return backupPath;
    }

    /**
     * Parses a JSON string and returns "data", "type", and "dataset" fields.
     * In the case of legacy JSON files with no type, they are assumed to be TradeBotStates archives,
     * as we had never implemented this for any other types.
     *
     * @param jsonString
     * @return Triple<String, String, JSONArray> (type, dataset, data)
     */
    public static Triple<String, String, JSONArray> parseJSONString(String jsonString) throws DataException {
        String type = null;
        String dataset = null;
        JSONArray data = null;

        try {
            // Firstly try importing the new format
            JSONObject jsonData = new JSONObject(jsonString);
            if (jsonData != null && jsonData.getString("type") != null) {

                type = jsonData.getString("type");
                dataset = jsonData.getString("dataset");
                data = jsonData.getJSONArray("data");
            }

        } catch (JSONException e) {
            // Could be a legacy format which didn't contain a type or any other outer keys, so try importing that
            // Treat these as TradeBotStates archives, given that this was the only type previously implemented
            try {
                type = "tradeBotStates";
                dataset = "archive";
                data = new JSONArray(jsonString);

            } catch (JSONException e2) {
                // Still failed, so give up
                throw new DataException("Couldn't import JSON file");
            }
        }

        return new Triple(type, dataset, data);
    }

}
