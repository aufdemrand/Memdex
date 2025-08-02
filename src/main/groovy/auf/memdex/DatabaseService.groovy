package auf.memdex

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

class DatabaseService {
    private final File dbRoot = new File('database')

    DatabaseService() {
        if (!dbRoot.exists()) {
            dbRoot.mkdir()
        }
    }

    /**
     * Creates a new record for a given module type. Optionally specify a UUID (for singletons).
     * @return The UUID of the newly created record.
     */
    String createRecord(String moduleType, String uuid = null) {
        if (!uuid) {
            uuid = UUID.randomUUID().toString()
        }
        def recordFile = new File(dbRoot, "${uuid}.json")
        def recordData = [
                uuid       : uuid,
                module_type: moduleType,
                created_at : new Date().toInstant().toString(),
                data       : [:] // To store record vars
        ]
        recordFile.write(new JsonBuilder(recordData).toPrettyString())
        return uuid
    }

    /**
     * Retrieves a record by its UUID.
     */
    Map<String, Object> getRecord(String uuid) {
        def recordFile = new File(dbRoot, "${uuid}.json")
        if (!recordFile.exists()) return null
        return new JsonSlurper().parse(recordFile) as Map<String, Object>
    }

    /**
     * Saves changes to a record.
     */
    void saveRecord(Map<String, Object> record) {
        def uuid = record.uuid
        if (!uuid) throw new IllegalArgumentException("Record must have a UUID.")
        def recordFile = new File(dbRoot, "${uuid}.json")
        recordFile.write(new JsonBuilder(record).toPrettyString())
    }

    /**
     * Deletes a record by its UUID.
     * @return true if the record was deleted, false if it didn't exist
     */
    boolean deleteRecord(String uuid) {
        def recordFile = new File(dbRoot, "${uuid}.json")
        if (recordFile.exists()) {
            return recordFile.delete()
        }
        return false
    }

    /**
     * Finds and returns the first record with the given module type.
     */
    Map<String, Object> findFirstByModuleType(String moduleType) {
        for (File file : dbRoot.listFiles({ dir, name -> name.endsWith('.json') } as FilenameFilter)) {
            def record = new JsonSlurper().parse(file) as Map<String, Object>
            if (record.module_type == moduleType) {
                return record
            }
        }
        return null
    }
}
