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
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.Test;
import org.owasp.dependencycheck.BaseDBTestCase;
import org.owasp.dependencycheck.Engine;

import static org.junit.Assert.assertNotNull;
import org.owasp.dependencycheck.data.update.nvd.NvdCveInfo;
import org.owasp.dependencycheck.utils.Settings;

/**
 *
 * @author Jeremy Long
 */
public class NvdCveUpdaterIT extends BaseDBTestCase {

    /**
     * Test of updatesNeeded method.
     */
    @Test
    public void testUpdatesNeeded() throws Exception {
    	//Thread.sleep(20000);
    	
    	long full = System.currentTimeMillis();
        NvdCveUpdater instance = new NvdCveUpdater();
        
        Settings settings = getSettings();
        settings.setInt(Settings.KEYS.CVE_START_YEAR, 2002);
        instance.setSettings(settings);
        instance.initializeExecutorServices();
        
        Engine engine = new Engine(getSettings());
        engine.openDatabase();
        
        System.out.println("Connect using " + getSettings().getString(Settings.KEYS.DB_CONNECTION_STRING));
        
        long time = System.currentTimeMillis();
        System.out.println("***************** Start");
		instance.update(engine, false);
        System.out.println("***************** End in " + (System.currentTimeMillis() - time) + "ms");
        
        System.out.println((System.currentTimeMillis() - full));
        
        String[] tables = new String[] {"software", "cpeEntry", "reference", "vulnerability", "properties", "cweEntry"};
        
        try (Connection conn = engine.getDatabase().getConnection()) {
        	try (Statement statement = conn.createStatement()) {
        		for(String table : tables) {
	        		ResultSet executeQuery = statement.executeQuery("SELECT COUNT(*) AS rows FROM " + table);
	        		executeQuery.next();
	        		int int1 = executeQuery.getInt("rows");
	        		System.out.println("Got " + int1 + " rows for " + table);
        		}
    		}
        } catch (SQLException e) {
        	e.printStackTrace();
        	throw new RuntimeException(e);
		}        
          
        
    }
}
