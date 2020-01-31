/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2018 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.nvdcve;
//CSOFF: AvoidStarImport

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.collections.map.ReferenceMap;
import org.owasp.dependencycheck.dependency.Vulnerability;
import org.owasp.dependencycheck.dependency.VulnerableSoftware;
import org.owasp.dependencycheck.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.commons.collections.map.AbstractReferenceMap.HARD;
import static org.apache.commons.collections.map.AbstractReferenceMap.SOFT;
import org.apache.commons.lang3.StringUtils;
import org.owasp.dependencycheck.analyzer.AbstractNpmAnalyzer;
import org.owasp.dependencycheck.analyzer.CMakeAnalyzer;
import org.owasp.dependencycheck.analyzer.ComposerLockAnalyzer;
import org.owasp.dependencycheck.analyzer.JarAnalyzer;
import org.owasp.dependencycheck.analyzer.NodeAuditAnalyzer;
import org.owasp.dependencycheck.analyzer.PythonPackageAnalyzer;
import org.owasp.dependencycheck.analyzer.RubyBundleAuditAnalyzer;
import org.owasp.dependencycheck.analyzer.RubyGemspecAnalyzer;
import org.owasp.dependencycheck.analyzer.exception.LambdaExceptionWrapper;
import org.owasp.dependencycheck.analyzer.exception.UnexpectedAnalysisException;
import org.owasp.dependencycheck.data.nvd.json.BaseMetricV2;
import org.owasp.dependencycheck.data.nvd.json.BaseMetricV3;
import org.owasp.dependencycheck.data.nvd.json.CpeMatchStreamCollector;
import org.owasp.dependencycheck.data.nvd.json.DefCpeMatch;
import org.owasp.dependencycheck.data.nvd.json.DefCveItem;
import org.owasp.dependencycheck.data.nvd.json.LangString;
import org.owasp.dependencycheck.data.nvd.json.NodeFlatteningCollector;
import org.owasp.dependencycheck.data.nvd.json.ProblemtypeDatum;
import org.owasp.dependencycheck.data.nvd.json.Reference;
import static org.owasp.dependencycheck.data.nvdcve.CveDB2.PreparedStatementCveDb.*;
import org.owasp.dependencycheck.data.update.cpe.CpePlus;
import org.owasp.dependencycheck.dependency.CvssV2;
import org.owasp.dependencycheck.dependency.CvssV3;
import org.owasp.dependencycheck.dependency.VulnerableSoftwareBuilder;
import us.springett.parsers.cpe.Cpe;
import us.springett.parsers.cpe.CpeBuilder;
import us.springett.parsers.cpe.CpeParser;
import us.springett.parsers.cpe.exceptions.CpeParsingException;
import us.springett.parsers.cpe.exceptions.CpeValidationException;

/**
 * The database holding information about the NVD CVE data. This class is safe
 * to be accessed from multiple threads in parallel, however internally only one
 * connection will be used.
 *
 * @author Jeremy Long
 */
@ThreadSafe
public final class CveDB2 implements AutoCloseable {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CveDB2.class);

    /**
     * The database connection factory.
     */
    private final ConnectionFactory connectionFactory;
    /**
     * Database connection
     */
    private Connection connection;
    /**
     * The bundle of statements used when accessing the database.
     */
    private ResourceBundle statementBundle;
    /**
     * Database properties object containing the 'properties' from the database
     * table.
     */
    private DatabaseProperties databaseProperties;
    /**
     * The prepared statements.
     */
    
    private ThreadLocal<EnumMap<PreparedStatementCveDb, PreparedStatement>> preparedStatements = new ThreadLocal<>();

    /**
     * A reference to the vulnerable software builder.
     */
    private final VulnerableSoftwareBuilder vulnerableSoftwareBuilder = new VulnerableSoftwareBuilder();
    /**
     * The filter for 2.3 CPEs in the CVEs - we don't import unless we get a
     * match.
     */
    private final String cpeStartsWithFilter;
    /**
     * Cache for CVE lookups; used to speed up the vulnerability search process.
     */
    @SuppressWarnings("unchecked")
    /**
     * The configured settings
     */
    private final Settings settings;
    
    private final Cache<String, List<Vulnerability>> vulnerabilitiesForCpeCache = CacheBuilder.newBuilder().softValues().build();
    
    /**
     * Analyzes the description to determine if the vulnerability/software is
     * for a specific known ecosystem. The ecosystem can be used later for
     * filtering CPE matches.
     *
     * @param description the description to analyze
     * @return the ecosystem if one could be identified; otherwise
     * <code>null</code>
     */
    private String determineBaseEcosystem(String description) {
        if (description == null) {
            return null;
        }
        int idx = StringUtils.indexOfIgnoreCase(description, ".php");
        if ((idx > 0 && (idx + 4 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 4))))
                || StringUtils.containsIgnoreCase(description, "wordpress")
                || StringUtils.containsIgnoreCase(description, "drupal")
                || StringUtils.containsIgnoreCase(description, "joomla")
                || StringUtils.containsIgnoreCase(description, "moodle")
                || StringUtils.containsIgnoreCase(description, "typo3")) {
            return ComposerLockAnalyzer.DEPENDENCY_ECOSYSTEM;
        }
        if (StringUtils.containsIgnoreCase(description, " npm ")
                || StringUtils.containsIgnoreCase(description, " node.js")) {
            return AbstractNpmAnalyzer.NPM_DEPENDENCY_ECOSYSTEM;
        }
        idx = StringUtils.indexOfIgnoreCase(description, ".pm");
        if (idx > 0 && (idx + 3 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 3)))) {
            return "perl";
        } else {
            idx = StringUtils.indexOfIgnoreCase(description, ".pl");
            if (idx > 0 && (idx + 3 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 3)))) {
                return "perl";
            }
        }
        idx = StringUtils.indexOfIgnoreCase(description, ".java");
        if (idx > 0 && (idx + 5 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 5)))) {
            return JarAnalyzer.DEPENDENCY_ECOSYSTEM;
        } else {
            idx = StringUtils.indexOfIgnoreCase(description, ".jsp");
            if (idx > 0 && (idx + 4 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 4)))) {
                return JarAnalyzer.DEPENDENCY_ECOSYSTEM;
            }
        }
        if (StringUtils.containsIgnoreCase(description, " grails ")) {
            return JarAnalyzer.DEPENDENCY_ECOSYSTEM;
        }

        idx = StringUtils.indexOfIgnoreCase(description, ".rb");
        if (idx > 0 && (idx + 3 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 3)))) {
            return RubyBundleAuditAnalyzer.DEPENDENCY_ECOSYSTEM;
        }
        if (StringUtils.containsIgnoreCase(description, "ruby gem")) {
            return RubyBundleAuditAnalyzer.DEPENDENCY_ECOSYSTEM;
        }

        idx = StringUtils.indexOfIgnoreCase(description, ".py");
        if ((idx > 0 && (idx + 3 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 3))))
                || StringUtils.containsIgnoreCase(description, "django")) {
            return PythonPackageAnalyzer.DEPENDENCY_ECOSYSTEM;
        }

        if (StringUtils.containsIgnoreCase(description, "buffer overflow")
                && !StringUtils.containsIgnoreCase(description, "android")) {
            return CMakeAnalyzer.DEPENDENCY_ECOSYSTEM;
        }
        idx = StringUtils.indexOfIgnoreCase(description, ".c");
        if (idx > 0 && (idx + 2 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 2)))) {
            return CMakeAnalyzer.DEPENDENCY_ECOSYSTEM;
        } else {
            idx = StringUtils.indexOfIgnoreCase(description, ".cpp");
            if (idx > 0 && (idx + 4 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 4)))) {
                return CMakeAnalyzer.DEPENDENCY_ECOSYSTEM;
            } else {
                idx = StringUtils.indexOfIgnoreCase(description, ".h");
                if (idx > 0 && (idx + 2 == description.length() || !Character.isLetterOrDigit(description.charAt(idx + 2)))) {
                    return CMakeAnalyzer.DEPENDENCY_ECOSYSTEM;
                }
            }
        }
        return null;
    }

    /**
     * Attempts to determine the ecosystem based on the vendor, product and
     * targetSw.
     *
     * @param baseEcosystem the base ecosystem
     * @param vendor the vendor
     * @param product the product
     * @param targetSw the target software
     * @return the ecosystem if one is identified
     */
    private String determineEcosystem(String baseEcosystem, String vendor, String product, String targetSw) {
        if ("ibm".equals(vendor) && "java".equals(product)) {
            return "c/c++";
        }
        if ("oracle".equals(vendor) && "vm".equals(product)) {
            return "c/c++";
        }
        if ("*".equals(targetSw) || baseEcosystem != null) {
            return baseEcosystem;
        }
        return targetSw;
    }

    /**
     * The enum value names must match the keys of the statements in the
     * statement bundles "dbStatements*.properties".
     */
    enum PreparedStatementCveDb {
        /**
         * Key for SQL Statement.
         */
        CLEANUP_ORPHANS,
        /**
         * Key for update ecosystem.
         */
        UPDATE_ECOSYSTEM,
        /**
         * Key for SQL Statement.
         */
        COUNT_CPE,
        /**
         * Key for SQL Statement.
         */
        DELETE_REFERENCE,
        /**
         * Key for SQL Statement.
         */
        DELETE_SOFTWARE,
        /**
         * Key for SQL Statement.
         */
        DELETE_CWE,
        /**
         * Key for SQL Statement.
         */
        DELETE_VULNERABILITY,
        /**
         * Key for SQL Statement.
         */
        INSERT_CPE,
        /**
         * Key for SQL Statement.
         */
        INSERT_PROPERTY,
        /**
         * Key for SQL Statement.
         */
        INSERT_CWE,
        /**
         * Key for SQL Statement.
         */
        INSERT_REFERENCE,
        /**
         * Key for SQL Statement.
         */
        INSERT_SOFTWARE,
        /**
         * Key for SQL Statement.
         */
        INSERT_VULNERABILITY,
        /**
         * Key for SQL Statement.
         */
        MERGE_PROPERTY,
        /**
         * Key for SQL Statement.
         */
        SELECT_CPE_ENTRIES,
        /**
         * Key for SQL Statement.
         */
        SELECT_CPE_ID,
        /**
         * Key for SQL Statement.
         */
        SELECT_CVE_FROM_SOFTWARE,
        /**
         * Key for SQL Statement.
         */
        SELECT_PROPERTIES,
        /**
         * Key for SQL Statement.
         */
        SELECT_VULNERABILITY_CWE,
        /**
         * Key for SQL Statement.
         */
        SELECT_REFERENCES,
        /**
         * Key for SQL Statement.
         */
        SELECT_SOFTWARE,
        /**
         * Key for SQL Statement.
         */
        SELECT_VENDOR_PRODUCT_LIST,
        /**
         * Key for SQL Statement.
         */
        SELECT_VULNERABILITY,
        /**
         * Key for SQL Statement.
         */
        SELECT_VULNERABILITY_ID,
        /**
         * Key for SQL Statement.
         */
        UPDATE_PROPERTY,
        /**
         * Key for SQL Statement.
         */
        UPDATE_VULNERABILITY
    }

    /**
     * Creates a new CveDB object and opens the database connection. Note, the
     * connection must be closed by the caller by calling the close method.
     *
     * @param settings the configured settings
     * @throws DatabaseException thrown if there is an exception opening the
     * database.
     */
    public CveDB2(Settings settings) throws DatabaseException {
        this.settings = settings;
        this.cpeStartsWithFilter = this.settings.getString(Settings.KEYS.CVE_CPE_STARTS_WITH_FILTER, "cpe:2.3:a:");
        connectionFactory = new ConnectionFactory(settings);
        open();
    }

    /**
     * Tries to determine the product name of the database.
     *
     * @param conn the database connection
     * @return the product name of the database if successful, {@code null} else
     */
    private String determineDatabaseProductName(Connection conn) {
        try {
            final String databaseProductName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            LOGGER.debug("Database product: {}", databaseProductName);
            return databaseProductName;
        } catch (SQLException se) {
            LOGGER.warn("Problem determining database product!", se);
            return null;
        }
    }

    /**
     * Opens the database connection. If the database does not exist, it will
     * create a new one.
     *
     * @throws DatabaseException thrown if there is an error opening the
     * database connection
     */
    private synchronized void open() throws DatabaseException {
        try {
            if (!isOpen()) {
                connection = connectionFactory.getConnection();
                final String databaseProductName = determineDatabaseProductName(this.connection);
                statementBundle = databaseProductName != null
                        ? ResourceBundle.getBundle("data/dbStatements", new Locale(databaseProductName))
                        : ResourceBundle.getBundle("data/dbStatements");
              //  databaseProperties = new DatabaseProperties(this);
            }
        } catch (DatabaseException e) {
            releaseResources();
            throw e;
        }
    }

    /**
     * Closes the database connection. Close should be called on this object
     * when it is done being used.
     */
    @Override
    public synchronized void close() {
        if (isOpen()) {
            clearCache();
            closeStatements();
            try {
                connection.close();
            } catch (SQLException ex) {
                LOGGER.error("There was an error attempting to close the CveDB, see the log for more details.");
                LOGGER.debug("", ex);
            } catch (Throwable ex) {
                LOGGER.error("There was an exception attempting to close the CveDB, see the log for more details.");
                LOGGER.debug("", ex);
            }
            releaseResources();
            connectionFactory.cleanup();
        }
    }

    /**
     * Releases the resources used by CveDB.
     */
    private synchronized void releaseResources() {
        statementBundle = null;
        
        closeStatements();
        
        databaseProperties = null;
        connection = null;
    }

    /**
     * Returns whether the database connection is open or closed.
     *
     * @return whether the database connection is open or closed
     */
    protected boolean isOpen() {
        return connection != null;
    }

    /**
     * Creates a prepared statement from the given key. The SQL is stored in a
     * properties file and the key is used to lookup the specific query.
     *
     * @param key the key to select the prepared statement from the properties
     * file
     * @return the prepared statement
     * @throws DatabaseException throw if there is an error generating the
     * prepared statement
     */
    private PreparedStatement prepareStatement(PreparedStatementCveDb key) throws DatabaseException {
        PreparedStatement preparedStatement = null;
        try {
            final String statementString = statementBundle.getString(key.name());
            if (key == INSERT_VULNERABILITY || key == INSERT_CPE) {
                final String[] returnedColumns = {"id"};
                preparedStatement = connection.prepareStatement(statementString, returnedColumns);
            } else {
                preparedStatement = connection.prepareStatement(statementString);
            }
        } catch (SQLException ex) {
            throw new DatabaseException(ex);
        } catch (MissingResourceException ex) {
            if (!ex.getMessage().contains("key MERGE_PROPERTY")) {
                throw new DatabaseException(ex);
            }
        }
        return preparedStatement;
    }

    /**
     * Closes all prepared statements.
     */
    private void closeStatements() {
    	preparedStatements.get().values().forEach((preparedStatement) -> DBUtils.closeStatement(preparedStatement));
    	preparedStatements.remove();
    }

    /**
     * Returns the specified prepared statement.
     *
     * @param key the prepared statement from {@link PreparedStatementCveDb} to
     * return
     * @return the prepared statement
     * @throws SQLException thrown if a SQL Exception occurs
     */
    private PreparedStatement getPreparedStatement(PreparedStatementCveDb key) throws SQLException {
    	EnumMap<PreparedStatementCveDb, PreparedStatement> enumMap = preparedStatements.get();
    	if(true || enumMap == null) {
    		return prepareStatement(key);
    	}
    	PreparedStatement preparedStatement = enumMap.get(key);
        if (preparedStatement != null) {
        	preparedStatement.clearParameters();
        	preparedStatement.clearBatch();
        	preparedStatement.clearWarnings();
            return preparedStatement;
        }
        
        preparedStatement = prepareStatement(key);
        if (key == null) {
            throw new SQLException("Database query does not exist in the resource bundle: " + key);
        }

        enumMap.put(key, preparedStatement);
        
        return preparedStatement;
    }

    /**
     * Commits all completed transactions.
     *
     * @throws SQLException thrown if a SQL Exception occurs
     */
    @SuppressWarnings("EmptyMethod")
    public synchronized void commit() throws SQLException {
        //temporary remove this as autocommit is on.
        //if (isOpen()) {
        //    connection.commit();
        //}
    }

    /**
     * Cleans up the object and ensures that "close" has been called.
     *
     * @throws Throwable thrown if there is a problem
     */
    @Override
    @SuppressWarnings("FinalizeDeclaration")
    protected void finalize() throws Throwable {
        LOGGER.debug("Entering finalize");
        close();
        super.finalize();
    }

    /**
     * Get the value of databaseProperties.
     *
     * @return the value of databaseProperties
     */
    public synchronized DatabaseProperties getDatabaseProperties() {
        return databaseProperties;
    }

    /**
     * Used within the unit tests to reload the database properties.
     *
     * @return the database properties
     */
    protected synchronized DatabaseProperties reloadProperties() {
        //databaseProperties = new DatabaseProperties(this);
        return databaseProperties;
    }

    /**
     * Searches the CPE entries in the database and retrieves all entries for a
     * given vendor and product combination. The returned list will include all
     * versions of the product that are registered in the NVD CVE data.
     *
     * @param vendor the identified vendor name of the dependency being analyzed
     * @param product the identified name of the product of the dependency being
     * analyzed
     * @return a set of vulnerable software
     */
    public synchronized Set<CpePlus> getCPEs(String vendor, String product) {
        final Set<CpePlus> cpe = new HashSet<>();
        ResultSet rs = null;
        try {
            final PreparedStatement ps = getPreparedStatement(SELECT_CPE_ENTRIES);
            if (ps == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_CPE_ENTRIES);
            }
            //part, vendor, product, version, update_version, edition,
            //lang, sw_edition, target_sw, target_hw, other, ecosystem
            ps.setString(1, vendor);
            ps.setString(2, product);
            rs = ps.executeQuery();
            final CpeBuilder builder = new CpeBuilder();
            while (rs.next()) {
                final Cpe entry = builder
                        .part(rs.getString(1))
                        .vendor(rs.getString(2))
                        .product(rs.getString(3))
                        .version(rs.getString(4))
                        .update(rs.getString(5))
                        .edition(rs.getString(6))
                        .language(rs.getString(7))
                        .swEdition(rs.getString(8))
                        .targetSw(rs.getString(9))
                        .targetHw(rs.getString(10))
                        .other(rs.getString(11)).build();
                final CpePlus plus = new CpePlus(entry, rs.getString(12));
                cpe.add(plus);
            }
        } catch (SQLException | CpeParsingException | CpeValidationException ex) {
            LOGGER.error("An unexpected SQL Exception occurred; please see the verbose log for more details.");
            LOGGER.debug("", ex);
        } finally {
            DBUtils.closeResultSet(rs);
        }
        return cpe;
    }

    /**
     * Returns the entire list of vendor/product combinations.
     *
     * @return the entire list of vendor/product combinations
     * @throws DatabaseException thrown when there is an error retrieving the
     * data from the DB
     */
    public synchronized Set<Pair<String, String>> getVendorProductList() throws DatabaseException {
        final Set<Pair<String, String>> data = new HashSet<>();
        ResultSet rs = null;
        try {
            final PreparedStatement ps = getPreparedStatement(SELECT_VENDOR_PRODUCT_LIST);
            if (ps == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_VENDOR_PRODUCT_LIST);
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                data.add(new Pair<>(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException ex) {
            final String msg = "An unexpected SQL Exception occurred; please see the verbose log for more details.";
            throw new DatabaseException(msg, ex);
        } finally {
            DBUtils.closeResultSet(rs);
        }
        return data;
    }

    /**
     * Returns a set of properties.
     *
     * @return the properties from the database
     */
    public synchronized Properties getProperties() {
        final Properties prop = new Properties();
        ResultSet rs = null;
        try {
            final PreparedStatement ps = getPreparedStatement(SELECT_PROPERTIES);
            if (ps == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_PROPERTIES);
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                prop.setProperty(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException ex) {
            LOGGER.error("An unexpected SQL Exception occurred; please see the verbose log for more details.");
            LOGGER.debug("", ex);
        } finally {
            DBUtils.closeResultSet(rs);
        }
        return prop;
    }

    /**
     * Saves a property to the database.
     *
     * @param key the property key
     * @param value the property value
     */
    public synchronized void saveProperty(String key, String value) {
        clearCache();
        try {
            final PreparedStatement mergeProperty = getPreparedStatement(MERGE_PROPERTY);
            if (mergeProperty != null) {
                mergeProperty.setString(1, key);
                mergeProperty.setString(2, value);
                mergeProperty.execute();
            } else {
                // No Merge statement, so doing an Update/Insert...
                final PreparedStatement updateProperty = getPreparedStatement(UPDATE_PROPERTY);
                if (updateProperty == null) {
                    throw new SQLException("Database query does not exist in the resource bundle: " + UPDATE_PROPERTY);
                }
                updateProperty.setString(1, value);
                updateProperty.setString(2, key);
                if (updateProperty.executeUpdate() == 0) {
                    final PreparedStatement insertProperty = getPreparedStatement(INSERT_PROPERTY);
                    if (insertProperty == null) {
                        throw new SQLException("Database query does not exist in the resource bundle: " + INSERT_PROPERTY);
                    }
                    insertProperty.setString(1, key);
                    insertProperty.setString(2, value);
                    insertProperty.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            LOGGER.warn("Unable to save property '{}' with a value of '{}' to the database", key, value);
            LOGGER.debug("", ex);
        }
    }

    /**
     * Clears cache. Should be called whenever something is modified. While this
     * is not the optimal cache eviction strategy, this is good enough for
     * typical usage (update DB and then only read) and it is easier to maintain
     * the code.
     * <p>
     * It should be also called when DB is closed.
     * </p>
     */
    private synchronized void clearCache() {
        vulnerabilitiesForCpeCache.invalidateAll();
    }

    /**
     * Retrieves the vulnerabilities associated with the specified CPE.
     *
     * @param cpe the CPE to retrieve vulnerabilities for
     * @return a list of Vulnerabilities
     * @throws DatabaseException thrown if there is an exception retrieving data
     */
    public synchronized List<Vulnerability> getVulnerabilities(Cpe cpe) throws DatabaseException {
    	String cpe23fs = cpe.toCpe23FS();
        final List<Vulnerability> cachedVulnerabilities = vulnerabilitiesForCpeCache.getIfPresent(cpe23fs);
        if (cachedVulnerabilities != null) {
            LOGGER.debug("Cache hit for {}", cpe23fs);
            return cachedVulnerabilities;
        } else {
            LOGGER.debug("Cache miss for {}", cpe23fs);
        }

        final List<Vulnerability> vulnerabilities = new ArrayList<>();
        ResultSet rs = null;
        try {
            final PreparedStatement ps = getPreparedStatement(SELECT_CVE_FROM_SOFTWARE);
            ps.setString(1, cpe.getVendor());
            ps.setString(2, cpe.getProduct());
            rs = ps.executeQuery();
            String currentCVE = "";

            final Set<VulnerableSoftware> vulnSoftware = new HashSet<>();
            while (rs.next()) {
                final String cveId = rs.getString(1);
                if (currentCVE.isEmpty()) {
                    //first loop we don't have the cveId
                    currentCVE = cveId;
                }
                if (!vulnSoftware.isEmpty() && !currentCVE.equals(cveId)) { //check for match and add
                    final VulnerableSoftware matchedCPE = getMatchingSoftware(cpe, vulnSoftware);
                    if (matchedCPE != null) {
                        final Vulnerability v = getVulnerability(currentCVE);
                        if (v != null) {
                            v.setMatchedVulnerableSoftware(matchedCPE);
                            v.setSource(Vulnerability.Source.NVD);
                            vulnerabilities.add(v);
                        }
                    }
                    vulnSoftware.clear();
                    currentCVE = cveId;
                }
                // 1 cve, 2 part, 3 vendor, 4 product, 5 version, 6 update_version, 7 edition,
                // 8 lang, 9 sw_edition, 10 target_sw, 11 target_hw, 12 other, 13 versionEndExcluding,
                //14 versionEndIncluding, 15 versionStartExcluding, 16 versionStartIncluding, 17 vulnerable
                final VulnerableSoftware vs;
                try {
                    vs = vulnerableSoftwareBuilder.part(rs.getString(2)).vendor(rs.getString(3))
                            .product(rs.getString(4)).version(rs.getString(5)).update(rs.getString(6))
                            .edition(rs.getString(7)).language(rs.getString(8)).swEdition(rs.getString(9))
                            .targetSw(rs.getString(10)).targetHw(rs.getString(11)).other(rs.getString(12))
                            .versionEndExcluding(rs.getString(13)).versionEndIncluding(rs.getString(14))
                            .versionStartExcluding(rs.getString(15)).versionStartIncluding(rs.getString(16))
                            .vulnerable(rs.getBoolean(17)).build();
                } catch (CpeParsingException | CpeValidationException ex) {
                    throw new DatabaseException("Database contains an invalid Vulnerable Software Entry", ex);
                }
                vulnSoftware.add(vs);
            }
            //remember to process the last set of CVE/CPE entries
            final VulnerableSoftware matchedCPE = getMatchingSoftware(cpe, vulnSoftware);
            if (matchedCPE != null) {
                final Vulnerability v = getVulnerability(currentCVE);
                if (v != null) {
                    v.setMatchedVulnerableSoftware(matchedCPE);
                    v.setSource(Vulnerability.Source.NVD);
                    vulnerabilities.add(v);
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Exception retrieving vulnerability for " + cpe.toCpe23FS(), ex);
        } finally {
            DBUtils.closeResultSet(rs);
        }
        vulnerabilitiesForCpeCache.put(cpe23fs, vulnerabilities);
        return vulnerabilities;
    }

    /**
     * Gets a vulnerability for the provided CVE.
     *
     * @param cve the CVE to lookup
     * @return a vulnerability object
     * @throws DatabaseException if an exception occurs
     */
    public synchronized Vulnerability getVulnerability(String cve) throws DatabaseException {
        ResultSet rsV = null;
        ResultSet rsC = null;
        ResultSet rsR = null;
        ResultSet rsS = null;
        Vulnerability vuln = null;

        try {
            final PreparedStatement psV = getPreparedStatement(SELECT_VULNERABILITY);
            if (psV == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_VULNERABILITY);
            }
            psV.setString(1, cve);
            rsV = psV.executeQuery();
            if (rsV.next()) {
                vuln = new Vulnerability();
                vuln.setName(cve);
                vuln.setDescription(rsV.getString(2));
                vuln.setSource(Vulnerability.Source.NVD);
                final int cveId = rsV.getInt(1);
                //id, 2.description, 3. cvssV22Score, 4 cvssV2AccessVector, 5 cvssV2AccessComplexity,
                //6 cvssV2Authentication, 7 cvssV2ConfidentialityImpact, 8 cvssV2IntegrityImpact,
                //9 cvssV2AvailabilityImpact, 10 cvssV2Severity
                if (rsV.getString(4) != null) {
                    final CvssV2 cvss = new CvssV2(rsV.getFloat(3), rsV.getString(4),
                            rsV.getString(5), rsV.getString(6), rsV.getString(7),
                            rsV.getString(7), rsV.getString(9), rsV.getString(10));
                    vuln.setCvssV2(cvss);
                }
                //11 cvssV3AttackVector, 12 cvssV3AttackComplexity, 13 cvssV3PrivilegesRequired,
                //14 cvssV3UserInteraction, 15 cvssV3Scope, 16 cvssV3ConfidentialityImpact,
                //17 cvssV3IntegrityImpact, 18 cvssV3AvailabilityImpact, 19 cvssV3BaseScore,
                //20 cvssV3BaseSeverity
                if (rsV.getString(11) != null) {
                    final CvssV3 cvss = new CvssV3(rsV.getString(11), rsV.getString(12),
                            rsV.getString(13), rsV.getString(14), rsV.getString(15),
                            rsV.getString(16), rsV.getString(17), rsV.getString(18),
                            rsV.getFloat(19), rsV.getString(20));
                    vuln.setCvssV3(cvss);
                }
                final PreparedStatement psCWE = getPreparedStatement(SELECT_VULNERABILITY_CWE);
                if (psCWE == null) {
                    throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_VULNERABILITY_CWE);
                }
                psCWE.setInt(1, cveId);
                rsC = psCWE.executeQuery();
                while (rsC.next()) {
                    vuln.addCwe(rsC.getString(1));
                }

                final PreparedStatement psR = getPreparedStatement(SELECT_REFERENCES);
                if (psR == null) {
                    throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_REFERENCES);
                }
                psR.setInt(1, cveId);
                rsR = psR.executeQuery();
                while (rsR.next()) {
                    vuln.addReference(rsR.getString(1), rsR.getString(2), rsR.getString(3));
                }

                final PreparedStatement psS = getPreparedStatement(SELECT_SOFTWARE);
                if (psS == null) {
                    throw new SQLException("Database query does not exist in the resource bundle: " + SELECT_SOFTWARE);
                }
                //1 part, 2 vendor, 3 product, 4 version, 5 update_version, 6 edition, 7 lang,
                //8 sw_edition, 9 target_sw, 10 target_hw, 11 other, 12 versionEndExcluding,
                //13 versionEndIncluding, 14 versionStartExcluding, 15 versionStartIncluding, 16 vulnerable
                psS.setInt(1, cveId);
                rsS = psS.executeQuery();
                while (rsS.next()) {
                    vulnerableSoftwareBuilder.part(rsS.getString(1))
                            .vendor(rsS.getString(2))
                            .product(rsS.getString(3))
                            .version(rsS.getString(4))
                            .update(rsS.getString(5))
                            .edition(rsS.getString(6))
                            .language(rsS.getString(7))
                            .swEdition(rsS.getString(8))
                            .targetSw(rsS.getString(9))
                            .targetHw(rsS.getString(10))
                            .other(rsS.getString(11))
                            .versionEndExcluding(rsS.getString(12))
                            .versionEndIncluding(rsS.getString(13))
                            .versionStartExcluding(rsS.getString(14))
                            .versionStartIncluding(rsS.getString(15))
                            .vulnerable(rsS.getBoolean(16));
                    vuln.addVulnerableSoftware(vulnerableSoftwareBuilder.build());
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Error retrieving " + cve, ex);
        } catch (CpeParsingException | CpeValidationException ex) {
            throw new DatabaseException("The database contains an invalid Vulnerable Software Entry", ex);
        } finally {
            DBUtils.closeResultSet(rsV);
            DBUtils.closeResultSet(rsC);
            DBUtils.closeResultSet(rsR);
            DBUtils.closeResultSet(rsS);
        }
        return vuln;
    }

    public void startVulnerabilityUpdate() {
    	preparedStatements.set(new EnumMap<>(PreparedStatementCveDb.class));
    }
    
    public void endVulnerabilityUpdate() {
    	closeStatements();
    }
    
    /**
     * Updates the vulnerability within the database. If the vulnerability does
     * not exist it will be added.
     *
     * @param cve the vulnerability from the NVD CVE Data Feed to add to the
     * database
     * @throws DatabaseException is thrown if the database
     */
    public void updateVulnerability(DefCveItem cve) {
    	
    	
    	
        //clearCache();
        final String cveId = cve.getCve().getCVEDataMeta().getId();
        try {
            int vulnerabilityId = updateVulnerabilityGetVulnerabilityId(cveId);

            final String description = cve.getCve().getDescription().getDescriptionData().stream().filter((desc)
                    -> "en".equals(desc.getLang())).map(d
                    -> d.getValue()).collect(Collectors.joining(" "));

            if (vulnerabilityId != 0) {
                //TODO what about cve.getCve().getCVEDataMeta().getSTATE()
//                if (description.contains("** REJECT **")) {
//                    updateVulnerabilityDeleteVulnerability(vulnerabilityId);
//                } else {
                updateVulnerabilityUpdateVulnerability(vulnerabilityId, cve, description);
//                }
            } else {
                vulnerabilityId = updateVulnerabilityInsertVulnerability(cve, description);
            }

            updateVulnerabilityInsertCwe(vulnerabilityId, cve);

            String baseEcosystem = determineBaseEcosystem(description);
            baseEcosystem = updateVulnerabilityInsertReferences(vulnerabilityId, cve, baseEcosystem);

            //parse the CPEs outside of a synchronized method
            final List<VulnerableSoftware> software = parseCpes(cve);

            updateVulnerabilityInsertSoftware(vulnerabilityId, cveId, software, baseEcosystem);

        } catch (SQLException ex) {
            final String msg = String.format("Error updating '%s'", cveId);
            LOGGER.debug(msg, ex);
            throw new DatabaseException(msg, ex);
        } catch (CpeValidationException ex) {
            final String msg = String.format("Error parsing CPE entry from '%s'", cveId);
            LOGGER.debug(msg, ex);
            throw new DatabaseException(msg, ex);
        }
    }

    /**
     * Used when updating a vulnerability - this method retrieves the
     * vulnerability ID from the database. If zero is returned the vulnerability
     * does not exist and must be inserted (created) instead of updated.
     *
     * @param cveId the CVE ID
     * @return the vulnerability ID
     */
    @SuppressFBWarnings(justification = "Try with resources will cleanup the resources", value = {"OBL_UNSATISFIED_OBLIGATION"})
    private int updateVulnerabilityGetVulnerabilityId(String cveId) {
        int vulnerabilityId = 0;
        try  {
        	PreparedStatement selectVulnerabilityId = getPreparedStatement(SELECT_VULNERABILITY_ID);
            PreparedStatement deleteReference = getPreparedStatement(DELETE_REFERENCE);
            PreparedStatement deleteSoftware = getPreparedStatement(DELETE_SOFTWARE);
            PreparedStatement deleteCwe = getPreparedStatement(DELETE_CWE);
            
            selectVulnerabilityId.setString(1, cveId);
            try (ResultSet rs = selectVulnerabilityId.executeQuery()) {
                if (rs.next()) {
                    vulnerabilityId = rs.getInt(1);
                    // first delete any existing vulnerability info. We don't know what was updated. yes, slower but atm easier.
                    deleteReference.setInt(1, vulnerabilityId);
                    deleteReference.execute();

                    deleteSoftware.setInt(1, vulnerabilityId);
                    deleteSoftware.execute();

                    deleteCwe.setInt(1, vulnerabilityId);
                    deleteCwe.execute();
                }
            }
        } catch (SQLException ex) {
            throw new UnexpectedAnalysisException(ex);
        }
        return vulnerabilityId;
    }

    /**
     * Used when updating a vulnerability - this method inserts the
     * vulnerability entry itself.
     *
     * @param cve the CVE data
     * @param description the description of the CVE entry
     * @return the vulnerability ID
     */
    private int updateVulnerabilityInsertVulnerability(DefCveItem cve, String description) {
        final int vulnerabilityId;
        try {
        	PreparedStatement insertVulnerability = getPreparedStatement(INSERT_VULNERABILITY);
            if (insertVulnerability == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + INSERT_VULNERABILITY);
            }
            //cve, description, cvssV2Score, cvssV2AccessVector, cvssV2AccessComplexity, cvssV2Authentication,
            //cvssV2ConfidentialityImpact, cvssV2IntegrityImpact, cvssV2AvailabilityImpact, cvssV2Severity,
            //cvssV3AttackVector, cvssV3AttackComplexity, cvssV3PrivilegesRequired, cvssV3UserInteraction,
            //cvssV3Scope, cvssV3ConfidentialityImpact, cvssV3IntegrityImpact, cvssV3AvailabilityImpact,
            //cvssV3BaseScore, cvssV3BaseSeverity
            insertVulnerability.setString(1, cve.getCve().getCVEDataMeta().getId());
            insertVulnerability.setString(2, description);
            if (cve.getImpact().getBaseMetricV2() != null) {
                final BaseMetricV2 cvssv2 = cve.getImpact().getBaseMetricV2();
                insertVulnerability.setFloat(3, cvssv2.getCvssV2().getBaseScore().floatValue());
                insertVulnerability.setString(4, cvssv2.getCvssV2().getAccessVector().value());
                insertVulnerability.setString(5, cvssv2.getCvssV2().getAccessComplexity().value());
                insertVulnerability.setString(6, cvssv2.getCvssV2().getAuthentication().value());
                insertVulnerability.setString(7, cvssv2.getCvssV2().getConfidentialityImpact().value());
                insertVulnerability.setString(8, cvssv2.getCvssV2().getIntegrityImpact().value());
                insertVulnerability.setString(9, cvssv2.getCvssV2().getAvailabilityImpact().value());
                insertVulnerability.setString(10, cvssv2.getSeverity());
            } else {
                insertVulnerability.setNull(3, java.sql.Types.NULL);
                insertVulnerability.setNull(4, java.sql.Types.NULL);
                insertVulnerability.setNull(5, java.sql.Types.NULL);
                insertVulnerability.setNull(6, java.sql.Types.NULL);
                insertVulnerability.setNull(7, java.sql.Types.NULL);
                insertVulnerability.setNull(8, java.sql.Types.NULL);
                insertVulnerability.setNull(9, java.sql.Types.NULL);
                insertVulnerability.setNull(10, java.sql.Types.NULL);
            }
            if (cve.getImpact().getBaseMetricV3() != null) {
                final BaseMetricV3 cvssv3 = cve.getImpact().getBaseMetricV3();
                insertVulnerability.setString(11, cvssv3.getCvssV3().getAttackVector().value());
                insertVulnerability.setString(12, cvssv3.getCvssV3().getAttackComplexity().value());
                insertVulnerability.setString(13, cvssv3.getCvssV3().getPrivilegesRequired().value());
                insertVulnerability.setString(14, cvssv3.getCvssV3().getUserInteraction().value());
                insertVulnerability.setString(15, cvssv3.getCvssV3().getScope().value());
                insertVulnerability.setString(16, cvssv3.getCvssV3().getConfidentialityImpact().value());
                insertVulnerability.setString(17, cvssv3.getCvssV3().getIntegrityImpact().value());
                insertVulnerability.setString(18, cvssv3.getCvssV3().getAvailabilityImpact().value());
                insertVulnerability.setFloat(19, cvssv3.getCvssV3().getBaseScore().floatValue());
                insertVulnerability.setString(20, cvssv3.getCvssV3().getBaseSeverity().value());
            } else {
                insertVulnerability.setNull(11, java.sql.Types.NULL);
                insertVulnerability.setNull(12, java.sql.Types.NULL);
                insertVulnerability.setNull(13, java.sql.Types.NULL);
                insertVulnerability.setNull(14, java.sql.Types.NULL);
                insertVulnerability.setNull(15, java.sql.Types.NULL);
                insertVulnerability.setNull(16, java.sql.Types.NULL);
                insertVulnerability.setNull(17, java.sql.Types.NULL);
                insertVulnerability.setNull(18, java.sql.Types.NULL);
                insertVulnerability.setNull(19, java.sql.Types.NULL);
                insertVulnerability.setNull(20, java.sql.Types.NULL);
            }
           	insertVulnerability.execute();
            try (ResultSet rs = insertVulnerability.getGeneratedKeys()) {
                rs.next();
                vulnerabilityId = rs.getInt(1);
            } catch (SQLException ex) {
                final String msg = String.format("Unable to retrieve id for new vulnerability for '%s'", cve.getCve().getCVEDataMeta().getId());
                throw new DatabaseException(msg, ex);
            }
        } catch (SQLException ex) {
            throw new UnexpectedAnalysisException(ex);
        }
        return vulnerabilityId;
    }

    /**
     * Used when updating a vulnerability - this method updates the
     * vulnerability entry itself.
     *
     * @param vulnerabilityId the vulnerability ID
     * @param cve the CVE data
     * @param description the description of the CVE entry
     */
    private synchronized void updateVulnerabilityUpdateVulnerability(int vulnerabilityId, DefCveItem cve, String description) {
        try {
        	PreparedStatement updateVulnerability = getPreparedStatement(UPDATE_VULNERABILITY);
            if (updateVulnerability == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + UPDATE_VULNERABILITY);
            }
            //description=?, cvssV2Score=?, cvssV2AccessVector=?, cvssV2AccessComplexity=?, cvssV2Authentication=?, cvssV2ConfidentialityImpact=?,
            //cvssV2IntegrityImpact=?, cvssV2AvailabilityImpact=?, cvssV2Severity=?, cvssV3AttackVector=?, cvssV3AttackComplexity=?,
            //cvssV3PrivilegesRequired=?, cvssV3UserInteraction=?, cvssV3Scope=?, cvssV3ConfidentialityImpact=?, cvssV3IntegrityImpact=?,
            //cvssV3AvailabilityImpact=?, cvssV3BaseScore=?, cvssV3BaseSeverity=? WHERE id=?
            updateVulnerability.setString(1, description);
            if (cve.getImpact().getBaseMetricV2() != null) {
                final BaseMetricV2 cvssv2 = cve.getImpact().getBaseMetricV2();
                updateVulnerability.setFloat(2, cvssv2.getCvssV2().getBaseScore().floatValue());
                updateVulnerability.setString(3, cvssv2.getCvssV2().getAccessVector().value());
                updateVulnerability.setString(4, cvssv2.getCvssV2().getAccessComplexity().value());
                updateVulnerability.setString(5, cvssv2.getCvssV2().getAuthentication().value());
                updateVulnerability.setString(6, cvssv2.getCvssV2().getConfidentialityImpact().value());
                updateVulnerability.setString(7, cvssv2.getCvssV2().getIntegrityImpact().value());
                updateVulnerability.setString(8, cvssv2.getCvssV2().getAvailabilityImpact().value());
                updateVulnerability.setString(9, cvssv2.getSeverity());
            } else {
                updateVulnerability.setNull(2, java.sql.Types.NULL);
                updateVulnerability.setNull(3, java.sql.Types.NULL);
                updateVulnerability.setNull(4, java.sql.Types.NULL);
                updateVulnerability.setNull(5, java.sql.Types.NULL);
                updateVulnerability.setNull(6, java.sql.Types.NULL);
                updateVulnerability.setNull(7, java.sql.Types.NULL);
                updateVulnerability.setNull(8, java.sql.Types.NULL);
                updateVulnerability.setNull(9, java.sql.Types.NULL);
            }

            //cvssV3AttackVector=?, cvssV3AttackComplexity=?, cvssV3PrivilegesRequired=?,
            //cvssV3UserInteraction=?, cvssV3Scope=?, cvssV3ConfidentialityImpact=?,
            //cvssV3IntegrityImpact=?, cvssV3AvailabilityImpact=?, cvssV3BaseScore=?,
            //cvssV3BaseSeverity
            if (cve.getImpact().getBaseMetricV3() != null) {
                final BaseMetricV3 cvssv3 = cve.getImpact().getBaseMetricV3();
                updateVulnerability.setString(10, cvssv3.getCvssV3().getAttackVector().value());
                updateVulnerability.setString(11, cvssv3.getCvssV3().getAttackComplexity().value());
                updateVulnerability.setString(12, cvssv3.getCvssV3().getPrivilegesRequired().value());
                updateVulnerability.setString(13, cvssv3.getCvssV3().getUserInteraction().value());
                updateVulnerability.setString(14, cvssv3.getCvssV3().getScope().value());
                updateVulnerability.setString(15, cvssv3.getCvssV3().getConfidentialityImpact().value());
                updateVulnerability.setString(16, cvssv3.getCvssV3().getIntegrityImpact().value());
                updateVulnerability.setString(17, cvssv3.getCvssV3().getAvailabilityImpact().value());
                updateVulnerability.setFloat(18, cvssv3.getCvssV3().getBaseScore().floatValue());
                updateVulnerability.setString(19, cvssv3.getCvssV3().getBaseSeverity().value());
            } else {
                updateVulnerability.setNull(10, java.sql.Types.NULL);
                updateVulnerability.setNull(11, java.sql.Types.NULL);
                updateVulnerability.setNull(12, java.sql.Types.NULL);
                updateVulnerability.setNull(13, java.sql.Types.NULL);
                updateVulnerability.setNull(14, java.sql.Types.NULL);
                updateVulnerability.setNull(15, java.sql.Types.NULL);
                updateVulnerability.setNull(16, java.sql.Types.NULL);
                updateVulnerability.setNull(17, java.sql.Types.NULL);
                updateVulnerability.setNull(18, java.sql.Types.NULL);
                updateVulnerability.setNull(19, java.sql.Types.NULL);
            }
            updateVulnerability.setInt(20, vulnerabilityId);
            updateVulnerability.executeUpdate();
        } catch (SQLException ex) {
            throw new UnexpectedAnalysisException(ex);
        }
    }

    /**
     * Used when updating a vulnerability - this method inserts the CWE entries.
     *
     * @param vulnerabilityId the vulnerability ID
     * @param cve the CVE entry that contains the CWE entries to insert
     * @throws SQLException thrown if there is an error inserting the data
     */
    private void updateVulnerabilityInsertCwe(int vulnerabilityId, DefCveItem cve) throws SQLException {
    	PreparedStatement insertCWE = getPreparedStatement(INSERT_CWE);
        try {
            insertCWE.setInt(1, vulnerabilityId);

            for (ProblemtypeDatum datum : cve.getCve().getProblemtype().getProblemtypeData()) {
                for (LangString desc : datum.getDescription()) {
                    if ("en".equals(desc.getLang())) {
                        insertCWE.setString(2, desc.getValue());
                        insertCWE.execute();
                    }
                }
            }
        } finally {
        	//insertCWE.clearParameters();
        }
    }

//    /**
//     * Used when updating a vulnerability - in some cases a CVE needs to be
//     * removed.
//     *
//     * @param vulnerabilityId the vulnerability ID
//     * @throws SQLException thrown if there is an error deleting the
//     * vulnerability
//     */
//    private synchronized void updateVulnerabilityDeleteVulnerability(int vulnerabilityId) throws SQLException {
//        try (PreparedStatement deleteVulnerability = prepareStatement(DELETE_VULNERABILITY)) {
//            deleteVulnerability.setInt(1, vulnerabilityId);
//            deleteVulnerability.executeUpdate();
//        }
//    }
    /**
     * Used when updating a vulnerability - this method inserts the list of
     * vulnerable software.
     *
     * @param vulnerabilityId the vulnerability id
     * @param cveId the CVE ID - used for reporting
     * @param software the list of vulnerable software
     * @param baseEcosystem the ecosystem based off of the vulnerability
     * description
     * @throws DatabaseException thrown if there is an error inserting the data
     * @throws SQLException thrown if there is an error inserting the data
     */
    private synchronized void updateVulnerabilityInsertSoftware(int vulnerabilityId, String cveId,
            List<VulnerableSoftware> software, String baseEcosystem)
            throws DatabaseException, SQLException {
        try {
        	PreparedStatement insertCpe = getPreparedStatement(INSERT_CPE);
            PreparedStatement selectCpeId = getPreparedStatement(SELECT_CPE_ID);
            PreparedStatement insertSoftware = getPreparedStatement(INSERT_SOFTWARE);
            for (VulnerableSoftware parsedCpe : software) {
                int cpeProductId = 0;
                selectCpeId.setString(1, parsedCpe.getPart().getAbbreviation());
                selectCpeId.setString(2, parsedCpe.getVendor());
                selectCpeId.setString(3, parsedCpe.getProduct());
                selectCpeId.setString(4, parsedCpe.getVersion());
                selectCpeId.setString(5, parsedCpe.getUpdate());
                selectCpeId.setString(6, parsedCpe.getEdition());
                selectCpeId.setString(7, parsedCpe.getLanguage());
                selectCpeId.setString(8, parsedCpe.getSwEdition());
                selectCpeId.setString(9, parsedCpe.getTargetSw());
                selectCpeId.setString(10, parsedCpe.getTargetHw());
                selectCpeId.setString(11, parsedCpe.getOther());
                try (ResultSet rs = selectCpeId.executeQuery()) {
                    if (rs.next()) {
                        cpeProductId = rs.getInt(1);
                    }
                } catch (SQLException ex) {
                    throw new DatabaseException("Unable to get primary key for new cpe: " + parsedCpe.toCpe23FS(), ex);
                }
                if (cpeProductId == 0) {
                    insertCpe.setString(1, parsedCpe.getPart().getAbbreviation());
                    insertCpe.setString(2, parsedCpe.getVendor());
                    insertCpe.setString(3, parsedCpe.getProduct());
                    insertCpe.setString(4, parsedCpe.getVersion());
                    insertCpe.setString(5, parsedCpe.getUpdate());
                    insertCpe.setString(6, parsedCpe.getEdition());
                    insertCpe.setString(7, parsedCpe.getLanguage());
                    insertCpe.setString(8, parsedCpe.getSwEdition());
                    insertCpe.setString(9, parsedCpe.getTargetSw());
                    insertCpe.setString(10, parsedCpe.getTargetHw());
                    insertCpe.setString(11, parsedCpe.getOther());
                    final String ecosystem = determineEcosystem(baseEcosystem, parsedCpe.getVendor(),
                            parsedCpe.getProduct(), parsedCpe.getTargetSw());
                    addNullableStringParameter(insertCpe, 12, ecosystem);

                    insertCpe.executeUpdate();
                    cpeProductId = DBUtils.getGeneratedKey(insertCpe);
                }
                if (cpeProductId == 0) {
                    throw new DatabaseException("Unable to retrieve cpeProductId - no data returned");
                }

                insertSoftware.setInt(1, vulnerabilityId);
                insertSoftware.setInt(2, cpeProductId);
                addNullableStringParameter(insertSoftware, 3, parsedCpe.getVersionEndExcluding());
                addNullableStringParameter(insertSoftware, 4, parsedCpe.getVersionEndIncluding());
                addNullableStringParameter(insertSoftware, 5, parsedCpe.getVersionStartExcluding());
                addNullableStringParameter(insertSoftware, 6, parsedCpe.getVersionStartIncluding());
                insertSoftware.setBoolean(7, parsedCpe.isVulnerable());

                if (isBatchInsertEnabled()) {
                    insertSoftware.addBatch();
                } else {
                    try {
                        insertSoftware.execute();
                    } catch (SQLException ex) {
                        if (ex.getMessage().contains("Duplicate entry")) {
                            final String msg = String.format("Duplicate software key identified in '%s'", cveId);
                            LOGGER.info(msg, ex);
                        } else {
                            throw ex;
                        }
                    }
                }
            }
            if (isBatchInsertEnabled()) {
            	try {
            		executeBatch(cveId, insertSoftware);
            	} finally {
            		insertSoftware.clearBatch();
            	}
            }
        } finally {
        	
        }
    }

    /**
     * Used when updating a vulnerability - this method inserts the list of
     * references. In addition, this method attempts to determine the ecosystem
     * based on the references information.
     *
     * @param vulnerabilityId the vulnerability id
     * @param cve the CVE entry that contains the list of references
     * @param baseEcosystem the base ecosystem previously identified
     * @return an updated ecosystem string if an ecosystem was identified;
     * otherwise <code>null</code>
     * @throws SQLException thrown if there is an error inserting the data
     */
    private synchronized String updateVulnerabilityInsertReferences(int vulnerabilityId, DefCveItem cve, String baseEcosystem) throws SQLException {
        String ecosystem = baseEcosystem;
        try {
        	PreparedStatement insertReference = getPreparedStatement(INSERT_REFERENCE);
            if (insertReference == null) {
                throw new SQLException("Database query does not exist in the resource bundle: " + INSERT_REFERENCE);
            }
            int countReferences = 0;
            for (Reference r : cve.getCve().getReferences().getReferenceData()) {
                if (ecosystem == null) {
                    if (r.getUrl().contains("elixir-security-advisories")) {
                        ecosystem = "elixir";
                    } else if (r.getUrl().contains("ruby-lang.org")) {
                        ecosystem = RubyGemspecAnalyzer.DEPENDENCY_ECOSYSTEM;
                    } else if (r.getUrl().contains("python.org")) {
                        ecosystem = PythonPackageAnalyzer.DEPENDENCY_ECOSYSTEM;
                    } else if (r.getUrl().contains("drupal.org")) {
                        ecosystem = PythonPackageAnalyzer.DEPENDENCY_ECOSYSTEM;
                    } else if (r.getUrl().contains("npm")) {
                        ecosystem = NodeAuditAnalyzer.DEPENDENCY_ECOSYSTEM;
                    } else if (r.getUrl().contains("nodejs.org")) {
                        ecosystem = NodeAuditAnalyzer.DEPENDENCY_ECOSYSTEM;
                    } else if (r.getUrl().contains("nodesecurity.io")) {
                        ecosystem = NodeAuditAnalyzer.DEPENDENCY_ECOSYSTEM;
                    }
                }
                insertReference.setInt(1, vulnerabilityId);
                insertReference.setString(2, r.getName());
                insertReference.setString(3, r.getUrl());
                insertReference.setString(4, r.getRefsource());
                if (isBatchInsertEnabled()) {
                    insertReference.addBatch();
                    countReferences++;
                    if (countReferences % getBatchSize() == 0) {
                        insertReference.executeBatch();
                        LOGGER.trace(getLogForBatchInserts(countReferences, "Completed %s batch inserts to references table: %s"));
                        countReferences = 0;
                    } else if (countReferences == cve.getCve().getReferences().getReferenceData().size()) {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace(getLogForBatchInserts(countReferences, "Completed %s batch inserts to reference table: %s"));
                        }
                        insertReference.executeBatch();
                        countReferences = 0;
                    }
                } else {
                    insertReference.execute();
                }
            }
        } finally {
        	
        }
        return ecosystem;
    }

    /**
     * Parses the configuration entries from the CVE entry into a list of
     * VulnerableSoftware objects.
     *
     * @param cve the CVE to parse the vulnerable software entries from
     * @return the list of vulnerable software
     * @throws CpeValidationException if an invalid CPE is present
     */
    private List<VulnerableSoftware> parseCpes(DefCveItem cve) throws CpeValidationException {
        final List<VulnerableSoftware> software = new ArrayList<>();
        final List<DefCpeMatch> cpeEntries = cve.getConfigurations().getNodes().stream()
                .collect(new NodeFlatteningCollector())
                .collect(new CpeMatchStreamCollector())
                .filter(predicate -> predicate.getCpe23Uri().startsWith(cpeStartsWithFilter))
                //this single CPE entry causes nearly 100% FP - so filtering it at the source.
                .filter(entry -> !("CVE-2009-0754".equals(cve.getCve().getCVEDataMeta().getId())
                && "cpe:2.3:a:apache:apache:*:*:*:*:*:*:*:*".equals(entry.getCpe23Uri())))
                .collect(Collectors.toList());
        final VulnerableSoftwareBuilder builder = new VulnerableSoftwareBuilder();

        try {
            cpeEntries.forEach(entry -> {
                builder.cpe(parseCpe(entry, cve.getCve().getCVEDataMeta().getId()))
                        .versionEndExcluding(entry.getVersionEndExcluding())
                        .versionStartExcluding(entry.getVersionStartExcluding())
                        .versionEndIncluding(entry.getVersionEndIncluding())
                        .versionStartIncluding(entry.getVersionStartIncluding())
                        .vulnerable(entry.getVulnerable());
                try {
                    software.add(builder.build());
                } catch (CpeValidationException ex) {
                    throw new LambdaExceptionWrapper(ex);
                }
            });
        } catch (LambdaExceptionWrapper ex) {
            throw (CpeValidationException) ex.getCause();
        }
        return software;
    }

    /**
     * Helper method to convert a CpeMatch (generated code used in parsing the
     * NVD JSON) into a CPE object.
     *
     * @param cpe the CPE Match
     * @param cveId the CVE associated with the CPEMatch - used for error
     * reporting
     * @return the resulting CPE object
     * @throws DatabaseException thrown if there is an error converting the
     * CpeMatch into a CPE object
     */
    private Cpe parseCpe(DefCpeMatch cpe, String cveId) throws DatabaseException {
        Cpe parsedCpe;
        try {
            //the replace is a hack as the NVD does not properly escape backslashes in their JSON
            parsedCpe = CpeParser.parse(cpe.getCpe23Uri(), true);
        } catch (CpeParsingException ex) {
            LOGGER.debug("NVD (" + cveId + ") contain an invalid 2.3 CPE: " + cpe.getCpe23Uri());
            if (cpe.getCpe22Uri() != null && !cpe.getCpe22Uri().isEmpty()) {
                try {
                    parsedCpe = CpeParser.parse(cpe.getCpe22Uri(), true);
                } catch (CpeParsingException ex2) {
                    throw new DatabaseException("Unable to parse CPE: " + cpe.getCpe23Uri(), ex);
                }
            } else {
                throw new DatabaseException("Unable to parse CPE: " + cpe.getCpe23Uri(), ex);
            }
        }
        return parsedCpe;
    }

    /**
     * Returns the size of the batch.
     *
     * @return the size of the batch
     */
    private int getBatchSize() {
        int max;
        try {
            max = settings.getInt(Settings.KEYS.MAX_BATCH_SIZE);
        } catch (InvalidSettingException pE) {
            max = 1000;
        }
        return max;
    }

    /**
     * Determines whether or not batch insert is enabled.
     *
     * @return <code>true</code> if batch insert is enabled; otherwise
     * <code>false</code>
     */
    private boolean isBatchInsertEnabled() {
        boolean batch;
        try {
            batch = settings.getBoolean(Settings.KEYS.ENABLE_BATCH_UPDATES);
        } catch (InvalidSettingException pE) {
            //If there's no configuration, default is to not perform batch inserts
            batch = false;
        }
        return batch;
    }

    /**
     * Generates a logging message for batch inserts.
     *
     * @param pCountReferences the number of batch statements executed
     * @param pFormat a Java String.format string
     * @return the formated string
     */
    private String getLogForBatchInserts(int pCountReferences, String pFormat) {
        return String.format(pFormat, pCountReferences, new Date());
    }

    /**
     * Executes batch inserts of vulnerabilities when property
     * database.batchinsert.maxsize is reached.
     *
     * @param vulnId the vulnerability ID
     * @param statement the prepared statement to batch execute
     * @throws SQLException thrown when the batch cannot be executed
     */
    private void executeBatch(String vulnId, PreparedStatement statement)
            throws SQLException {
        try {
            statement.executeBatch();
        } catch (SQLException ex) {
            if (ex.getMessage().contains("Duplicate entry")) {
                final String msg = String.format("Duplicate software key identified in '%s'",
                        vulnId);
                LOGGER.info(msg, ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Checks to see if data exists so that analysis can be performed.
     *
     * @return <code>true</code> if data exists; otherwise <code>false</code>
     */
    public synchronized boolean dataExists() {
        ResultSet rs = null;
        try {
            final PreparedStatement cs = getPreparedStatement(COUNT_CPE);
            if (cs == null) {
                LOGGER.error("Unable to validate if data exists in the database");
                return false;
            }
            rs = cs.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                return true;
            }
        } catch (Exception ex) {
            String dd;
            try {
                dd = settings.getDataDirectory().getAbsolutePath();
            } catch (IOException ex1) {
                dd = settings.getString(Settings.KEYS.DATA_DIRECTORY);
            }
            LOGGER.error("Unable to access the local database.\n\nEnsure that '{}' is a writable directory. "
                    + "If the problem persist try deleting the files in '{}' and running {} again. If the problem continues, please "
                    + "create a log file (see documentation at http://jeremylong.github.io/DependencyCheck/) and open a ticket at "
                    + "https://github.com/jeremylong/DependencyCheck/issues and include the log file.\n\n",
                    dd, dd, settings.getString(Settings.KEYS.APPLICATION_NAME));
            LOGGER.debug("", ex);
        } finally {
            DBUtils.closeResultSet(rs);
        }
        return false;
    }

    /**
     * It is possible that orphaned rows may be generated during database
     * updates. This should be called after all updates have been completed to
     * ensure orphan entries are removed.
     */
    public synchronized void cleanupDatabase() {
        LOGGER.info("Begin database maintenance");
        final long start = System.currentTimeMillis();
        clearCache();
        try {
        	PreparedStatement psOrphans = getPreparedStatement(CLEANUP_ORPHANS);
            PreparedStatement psEcosystem = getPreparedStatement(UPDATE_ECOSYSTEM);
            if (psEcosystem != null) {
                psEcosystem.executeUpdate();
            }
            if (psOrphans != null) {
                psOrphans.executeUpdate();
            }
            final long millis = System.currentTimeMillis() - start;
            //final long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
            LOGGER.info("End database maintenance ({} ms)", millis);
        } catch (SQLException ex) {
            LOGGER.error("An unexpected SQL Exception occurred; please see the verbose log for more details.");
            LOGGER.debug("", ex);
            throw new DatabaseException("Unexpected SQL Exception", ex);
        } finally {
        	
        }
    }

    /**
     * If the database is using an H2 file based database calling
     * <code>defrag()</code> will de-fragment the database.
     */
    public synchronized void defrag() {
        if (ConnectionFactory.isH2Connection(settings)) {
            final long start = System.currentTimeMillis();
            try (CallableStatement psCompaxt = connection.prepareCall("SHUTDOWN DEFRAG")) {
                if (psCompaxt != null) {
                    LOGGER.info("Begin database defrag");
                    psCompaxt.execute();
                    final long millis = System.currentTimeMillis() - start;
                    //final long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
                    LOGGER.info("End database defrag ({} ms)", millis);
                }
            } catch (SQLException ex) {
                LOGGER.error("An unexpected SQL Exception occurred compacting the database; please see the verbose log for more details.");
                LOGGER.debug("", ex);
            }
        }
    }

    /**
     * Determines if the given identifiedVersion is affected by the given cpeId
     * and previous version flag. A non-null, non-empty string passed to the
     * previous version argument indicates that all previous versions are
     * affected.
     *
     * @param cpe the CPE for the given dependency
     * @param vulnerableSoftware a set of the vulnerable software
     * @return true if the identified version is affected, otherwise false
     */
    protected VulnerableSoftware getMatchingSoftware(Cpe cpe, Set<VulnerableSoftware> vulnerableSoftware) {
        VulnerableSoftware matched = null;
        for (VulnerableSoftware vs : vulnerableSoftware) {
            if (vs.matchedBy(cpe)) {
                if (matched == null) {
                    matched = vs;
                } else {
                    if ("*".equals(vs.getWellFormedUpdate()) && !"*".equals(matched.getWellFormedUpdate())) {
                        matched = vs;
                    }
                }
            }
        }
        return matched;
//        final boolean isVersionTwoADifferentProduct = "apache".equals(cpe.getVendor()) && "struts".equals(cpe.getProduct());
//        final Set<String> majorVersionsAffectingAllPrevious = new HashSet<>();
//        final boolean matchesAnyPrevious = identifiedVersion == null || "-".equals(identifiedVersion.toString());
//        String majorVersionMatch = null;
//        for (Entry<String, Boolean> entry : vulnerableSoftware.entrySet()) {
//            final DependencyVersion v = parseDependencyVersion(entry.getKey());
//            if (v == null || "-".equals(v.toString())) { //all versions
//                return entry;
//            }
//            if (entry.getValue()) {
//                if (matchesAnyPrevious) {
//                    return entry;
//                }
//                if (identifiedVersion != null && identifiedVersion.getVersionParts().get(0).equals(v.getVersionParts().get(0))) {
//                    majorVersionMatch = v.getVersionParts().get(0);
//                }
//                majorVersionsAffectingAllPrevious.add(v.getVersionParts().get(0));
//            }
//        }
//        if (matchesAnyPrevious) {
//            return null;
//        }
//
//        final boolean canSkipVersions = majorVersionMatch != null && majorVersionsAffectingAllPrevious.size() > 1;
//        //yes, we are iterating over this twice. The first time we are skipping versions those that affect all versions
//        //then later we process those that affect all versions. This could be done with sorting...
//        for (Entry<String, Boolean> entry : vulnerableSoftware.entrySet()) {
//            if (!entry.getValue()) {
//                final DependencyVersion v = parseDependencyVersion(entry.getKey());
//                //this can't dereference a null 'majorVersionMatch' as canSkipVersions accounts for this.
//                if (canSkipVersions && majorVersionMatch != null && !majorVersionMatch.equals(v.getVersionParts().get(0))) {
//                    continue;
//                }
//                //this can't dereference a null 'identifiedVersion' because if it was null we would have exited
//                //in the above loop or just after loop (if matchesAnyPrevious return null).
//                if (identifiedVersion != null && identifiedVersion.equals(v)) {
//                    return entry;
//                }
//            }
//        }
//        for (Entry<String, Boolean> entry : vulnerableSoftware.entrySet()) {
//            if (entry.getValue()) {
//                final DependencyVersion v = parseDependencyVersion(entry.getKey());
//                //this can't dereference a null 'majorVersionMatch' as canSkipVersions accounts for this.
//                if (canSkipVersions && majorVersionMatch != null && !majorVersionMatch.equals(v.getVersionParts().get(0))) {
//                    continue;
//                }
//                //this can't dereference a null 'identifiedVersion' because if it was null we would have exited
//                //in the above loop or just after loop (if matchesAnyPrevious return null).
//                if (entry.getValue() && identifiedVersion != null && identifiedVersion.compareTo(v) <= 0
//                        && !(isVersionTwoADifferentProduct && !identifiedVersion.getVersionParts().get(0).equals(v.getVersionParts().get(0)))) {
//                    return entry;
//                }
//            }
//        }
//        return null;
    }

    /**
     * This method is only referenced in unused code.
     * <p>
     * Deletes unused dictionary entries from the database.
     * </p>
     */
    public synchronized void deleteUnusedCpe() {
        clearCache();
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(statementBundle.getString("DELETE_UNUSED_DICT_CPE"));
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOGGER.error("Unable to delete CPE dictionary entries", ex);
        } finally {
            DBUtils.closeStatement(ps);
        }
    }

    /**
     * This method is only referenced in unused code and will likely break on
     * MySQL if ever used due to the MERGE statement.
     * <p>
     * Merges CPE entries into the database.
     * </p>
     *
     * @param cpe the CPE identifier
     * @param vendor the CPE vendor
     * @param product the CPE product
     */
    public synchronized void addCpe(String cpe, String vendor, String product) {
        clearCache();
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(statementBundle.getString("ADD_DICT_CPE"));
            ps.setString(1, cpe);
            ps.setString(2, vendor);
            ps.setString(3, product);
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOGGER.error("Unable to add CPE dictionary entry", ex);
        } finally {
            DBUtils.closeStatement(ps);
        }
    }

    /**
     * Helper method to add a nullable string parameter.
     *
     * @param ps the prepared statement
     * @param pos the position of the parameter
     * @param value the value of the parameter
     * @throws SQLException thrown if there is an error setting the parameter.
     */
    private void addNullableStringParameter(PreparedStatement ps, int pos, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            ps.setNull(pos, java.sql.Types.VARCHAR);
        } else {
            ps.setString(pos, value);
        }
    }
}
