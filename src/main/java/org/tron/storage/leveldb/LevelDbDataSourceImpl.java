/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tron.storage.leveldb;

import org.iq80.leveldb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.config.Configer;
import org.tron.storage.DbSourceInter;
import org.tron.utils.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.fusesource.leveldbjni.JniDBFactory.factory;


public class LevelDbDataSourceImpl implements DbSourceInter<byte[]> {

    private static final Logger logger = LoggerFactory.getLogger("dbStore");

    private final static String LEVEL_DB_DIRECTORY = "database.directory";
    public final static String databaseName = Configer.getConf().getString(LEVEL_DB_DIRECTORY);

    private String dataBaseName;
    private DB database;
    private boolean alive;

    private ReadWriteLock resetDbLock = new ReentrantReadWriteLock();


    public LevelDbDataSourceImpl(String name) {
        this.dataBaseName = name;
        logger.debug("New LevelDbDataSourceImpl: " + name);
    }

    @Override
    public void initDB() {
        resetDbLock.writeLock().lock();
        try {
            logger.debug("~> LevelDbDataSourceImpl.initDB(): " + dataBaseName);

            if (isAlive()) return;

            if (dataBaseName == null) throw new NullPointerException("no name set to the dbStore");

            Options dbOptions = new Options();
            dbOptions.createIfMissing(true);
            dbOptions.compressionType(CompressionType.NONE);
            dbOptions.blockSize(10 * 1024 * 1024);
            dbOptions.writeBufferSize(10 * 1024 * 1024);
            dbOptions.cacheSize(0);
            dbOptions.paranoidChecks(true);
            dbOptions.verifyChecksums(true);
            dbOptions.maxOpenFiles(32);

            try {
                final Path dbPath = getDBPath();
                if (!Files.isSymbolicLink(dbPath.getParent())) Files.createDirectories(dbPath.getParent());
                try {
                    database = factory.open(dbPath.toFile(), dbOptions);
                } catch (IOException e) {
                    if (e.getMessage().contains("Corruption:")) {
                        factory.repair(dbPath.toFile(), dbOptions);
                        database = factory.open(dbPath.toFile(), dbOptions);
                    } else {
                        throw e;
                    }
                }
                alive = true;
            } catch (IOException ioe) {
                throw new RuntimeException("Can't initialize database", ioe);
            }
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }

    private Path getDBPath() {
        return Paths.get(databaseName, dataBaseName);
    }

    public void resetDB() {
        closeDB();
        FileUtil.recursiveDelete(getDBPath().toString());
        initDB();
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    public void destroyDB(File fileLocation) {
        resetDbLock.writeLock().lock();
        try {
            logger.debug("Destroying existing database: " + fileLocation);
            Options options = new Options();
            try {
                factory.destroy(fileLocation, options);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }

    @Override
    public void setDBName(String name) {
        this.dataBaseName = name;
    }

    @Override
    public String getDBName() {
        return dataBaseName;
    }

    @Override
    public byte[] getData(byte[] key) {
        resetDbLock.readLock().lock();
        try {
            return database.get(key);
        } catch (DBException e) {
            return database.get(key);
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public void putData(byte[] key, byte[] value) {
        resetDbLock.readLock().lock();
        try {
            database.put(key, value);
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public void deleteData(byte[] key) {
        resetDbLock.readLock().lock();
        try {
            database.delete(key);
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public Set<byte[]> allKeys() {
        resetDbLock.readLock().lock();
        try (DBIterator iterator = database.iterator()) {
            Set<byte[]> result = new HashSet<>();
            for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                result.add(iterator.peekNext().getKey());
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    private void updateByBatchInner(Map<byte[], byte[]> rows) throws Exception {
        try (WriteBatch batch = database.createWriteBatch()) {
            for (Map.Entry<byte[], byte[]> entry : rows.entrySet()) {
                if (entry.getValue() == null) {
                    batch.delete(entry.getKey());
                } else {
                    batch.put(entry.getKey(), entry.getValue());
                }
            }
            database.write(batch);
        }
    }

    @Override
    public void updateByBatch(Map<byte[], byte[]> rows) {
        resetDbLock.readLock().lock();
        try {
            updateByBatchInner(rows);
        } catch (Exception e) {
            try {
                updateByBatchInner(rows);
            } catch (Exception e1) {
                throw new RuntimeException(e);
            }
        } finally {
            resetDbLock.readLock().unlock();
        }
    }

    @Override
    public boolean flush() {
        return false;
    }

    @Override
    public void closeDB() {
        resetDbLock.writeLock().lock();
        if (!isAlive()) return;
        try {
            database.close();
            alive = false;
        } catch (IOException e) {
            logger.error("Failed to find the dbStore file on the closeDB: {} ", dataBaseName);
        } finally {
            resetDbLock.writeLock().unlock();
        }
    }
}
