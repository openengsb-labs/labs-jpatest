/**
 * Licensed to the Austrian Association for Software Tool Integration (AASTI)
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The AASTI licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openengsb.labs.jpatest.junit;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.PersistenceException;
import javax.persistence.metamodel.ManagedType;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestPersistenceUnit implements MethodRule {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestPersistenceUnit.class);

    private static Map<String, EntityManagerFactory> emCache = new HashMap<String, EntityManagerFactory>();

    private Set<EntityManagerFactory> usedPersistenceUnits = new HashSet<EntityManagerFactory>();
    private Set<EntityManager> createdEntityManagers = new HashSet<EntityManager>();
    private Server tcpServer;

    public TestPersistenceUnit() {
    }

    public TestPersistenceUnit(int port) {
        try {
            tcpServer = Server.createTcpServer("-tcpPort", "" + port);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int readPortFromStream(InputStream stream, String property) {
        Properties properties = new Properties();
        try {
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Integer.parseInt((String) properties.get(property));
    }

    private EntityManager makeEntityManager(EntityManagerFactory emf) {
        Properties emProperties = new Properties();
        emProperties.put("openjpa.TransactionMode", "local");
        return emf.createEntityManager(emProperties);
    }

    private EntityManagerFactory makeEntityManagerFactory(String s) throws SQLException {
        Properties props = new Properties();
        String url = String.format("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1", s);
        props.put("javax.persistence.jdbc.url", url);
        props.put("javax.persistence.jdbc.driver", "org.h2.Driver");
        props.put("javax.persistence.transactionType", "RESOURCE_LOCAL");

        // OpenJPA support
        props.put("openjpa.Connection2URL", url);
        props.put("openjpa.Connection2DriverName", "org.h2.Driver");
        props.put("openjpa.jdbc.SynchronizeMappings",
                "buildSchema(SchemaAction='add')");
        props.put("openjpa.ConnectionRetainMode", "always");
        props.put("openjpa.ConnectionFactoryMode", "local");
        // eclipse-link support
        props.put("eclipselink.ddl-generation", "create-tables");
        props.put("eclipselink.ddl-generation.output-mode", "database");
        // Hibernate support
        props.put("hibernate.hbm2ddl.auto", "create-drop");
        props.put("hibernate.connection.driver_class", "org.h2.Driver");
        props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        props.put("hibernate.connection.url", url);
        props.put("hibernate.show_sql", "true");
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        String dataSourceUrl = "java:/dummyDataSource_" + s;
        try {
            new InitialContext().bind(dataSourceUrl, dataSource);
        } catch (NamingException e) {
            throw new AssertionError(e);
        }
        props.put("hibernate.connection.datasource", dataSourceUrl);
        return Persistence.createEntityManagerFactory(s, props);
    }

    public EntityManager getEntityManager(String s) throws SQLException {
        EntityManagerFactory entityManagerFactory = getEntityManagerFactory(s);
        usedPersistenceUnits.add(entityManagerFactory);
        EntityManager entityManager = makeEntityManager(entityManagerFactory);
        createdEntityManagers.add(entityManager);
        return entityManager;
    }

    private EntityManagerFactory getEntityManagerFactory(String s) throws SQLException {
        if (!emCache.containsKey(s)) {
            emCache.put(s, makeEntityManagerFactory(s));
        }
        return emCache.get(s);
    }

    private class PersistenceStatement extends Statement {

        private Statement parent;

        private PersistenceStatement(Statement parent) {
            this.parent = parent;
        }

        @Override
        public void evaluate() throws Throwable {
            InitialContext initialContext;
            try {
                System.setProperty(Context.INITIAL_CONTEXT_FACTORY,  "org.apache.naming.java.javaURLContextFactory");
                System.setProperty(Context.URL_PKG_PREFIXES, "org.apache.naming");
                initialContext = new InitialContext(new Hashtable<String, Object>());
                initialContext.createSubcontext("java:");
            } catch (NamingException e) {
                throw new RuntimeException(e);
            }
            try {
                parent.evaluate();
                for (EntityManager e : createdEntityManagers) {
                    if (e.getTransaction().isActive()) {
                        e.getTransaction().rollback();
                        throw new AssertionError("EntityManager " + e + " left an open transaction");
                    }
                }
            } finally {
                initialContext.destroySubcontext("java:");
                initialContext.close();
                try {
                    for (EntityManager e : createdEntityManagers) {
                        e.close();
                    }
                } finally {
                    createdEntityManagers.clear();
                    for (EntityManagerFactory emf : usedPersistenceUnits) {
                        clearTables(emf);
                    }
                }
            }
        }

        private void clearTables(EntityManagerFactory emf) throws SQLException {
            long start = System.currentTimeMillis();
            Set<ManagedType<?>> types = emf.getMetamodel().getManagedTypes();
            EntityManager entityManager = makeEntityManager(emf);
            Set<Class<?>> javaTypes = new HashSet<Class<?>>();
            for (ManagedType<?> type : types) {
                javaTypes.add(type.getJavaType());
            }
            int lastsize = javaTypes.size();
            while (!javaTypes.isEmpty()) {
                Iterator<Class<?>> iterator = javaTypes.iterator();
                Collection<Exception> exceptionsDuringClean = new ArrayList<Exception>();
                while (iterator.hasNext()) {
                    Class<?> javaType = iterator.next();
                    String name = retrieveEntityName(javaType);
                    if (name == null) {
                        LOGGER.warn("could not determine name for entity {}", javaType);
                        iterator.remove();
                        continue;
                    }
                    try {
                        entityManager.getTransaction().begin();
                        entityManager.createQuery("DELETE FROM " + name).executeUpdate();
                        entityManager.getTransaction().commit();
                        iterator.remove();
                    } catch (Exception e) {
                        if (e instanceof PersistenceException
                                || e.getClass().getName().equals("org.eclipse.persistence.exceptions.DatabaseException") // for eclipse-link < 2.5.0
                                ) {
                            exceptionsDuringClean.add(e);
                            LOGGER.debug("error during delete, could be normal", e);
                            entityManager.getTransaction().rollback();
                        }
                    }
                }
                if (javaTypes.size() == lastsize) {
                    entityManager.getTransaction().begin();
                    entityManager.createNativeQuery("SHUTDOWN").executeUpdate();

                    try {
                        entityManager.getTransaction().commit();
                    } catch (Exception e) {
                        // will always fail because database is shutting down,
                        // but we need to clear the transaction-state in the entitymanager
                        break;
                    }
                    LOGGER.error("could not clean database", exceptionsDuringClean.iterator().next());
                }
                lastsize = javaTypes.size();
            }
            entityManager.close();
            LOGGER.info("cleared database in {}ms", System.currentTimeMillis() - start);
        }

        private String retrieveEntityName(Class<?> javaType) {
            Entity entity = javaType.getAnnotation(Entity.class);
            if (entity == null) {
                return null;
            }
            if (entity.name().isEmpty()) {
                return javaType.getSimpleName();
            }
            return entity.name();
        }
    }

    private class ServerSpawningStatement extends PersistenceStatement {
        private ServerSpawningStatement(Statement parent) {
            super(parent);
        }

        @Override
        public void evaluate() throws Throwable {
            tcpServer.start();
            LOGGER.info("TCP server started");
            try {
                super.evaluate();
            } finally {
                LOGGER.info("TCP server stopped");
                tcpServer.stop();
            }
        }
    }

    @Override
    public Statement apply(Statement statement, FrameworkMethod frameworkMethod, Object o) {
        if (tcpServer != null) {
            return new ServerSpawningStatement(statement);
        }
        return new PersistenceStatement(statement);
    }
}
