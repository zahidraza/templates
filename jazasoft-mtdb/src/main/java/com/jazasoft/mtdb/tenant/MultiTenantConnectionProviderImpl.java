package com.jazasoft.mtdb.tenant;

import com.jazasoft.mtdb.Constants;
import com.jazasoft.mtdb.TenantCreatedEvent;
import com.jazasoft.mtdb.entity.Company;
import com.jazasoft.mtdb.repository.CompanyRepository;
import com.jazasoft.mtdb.util.Utils;
import com.jazasoft.util.ScriptUtils;
import org.hibernate.engine.jdbc.connections.spi.AbstractDataSourceBasedMultiTenantConnectionProviderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by mdzahidraza on 26/06/17.
 */

@Component
@Scope( proxyMode = ScopedProxyMode.TARGET_CLASS )
@Transactional(value="masterTransactionManager", readOnly = true)
@Profile("default")
public class MultiTenantConnectionProviderImpl extends AbstractDataSourceBasedMultiTenantConnectionProviderImpl implements ApplicationListener<TenantCreatedEvent>{

    private static final long serialVersionUID = 6246085840652870138L;

    private final static Logger LOGGER = LoggerFactory.getLogger(MultiTenantConnectionProviderImpl.class);

    private Map<String, DataSource> map; // map holds the companyKey => DataSource

    @Autowired
    private CompanyRepository companyRepository;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.driverClassName}")
    private String driverClassName;

    @Value("${spring.datasource.username}")
    private String user;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.platform}")
    private String platform;

    @Autowired
    private DataSource dataSource; // injected here to get properties and to provide default.

    @PostConstruct
    public void load() {
        map = new HashMap<>();
        init();
    }

    public void init() {
        for (Company company : companyRepository.findAll()) {
            // in this experiment we are just using one instance of mysql. URL is generated by replacing master database
            // name with company key to get new database URL
            try {
                addDatasource(company.getDbName());
            } catch (Exception e) {
                LOGGER.error("Error in database URL {}", url, e);
            }
        }
    }

    @Override
    protected DataSource selectAnyDataSource() {
        LOGGER.debug("######### Selecting any data source");
        return dataSource;
    }

    @Override
    public DataSource selectDataSource(String tenantIdentifier) {
        LOGGER.info("+++++++++++ Selecting data source for {}", tenantIdentifier);
        return map.containsKey(tenantIdentifier) ? map.get(tenantIdentifier) : dataSource ;
    }

    @Override
    public void onApplicationEvent(TenantCreatedEvent tenantCreatedEvent) {

        addDatasource(tenantCreatedEvent.getDbName());

    }

    private void addDatasource(String tenantIdentifier) {
        LOGGER.debug("addDatasource");
        DataSource dataSource = getDatasource(tenantIdentifier);
        map.put(tenantIdentifier, dataSource);
        initDb(tenantIdentifier);
    }

    private DataSource getDatasource(String tenantId) {
        String newUrl = url.replace(Utils.databaseNameFromJdbcUrl(url), tenantId);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(newUrl);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        return dataSource;
    }


    private void initDb(String tenant) {
        LOGGER.info("initDb");
        String script = null;
        try {
            script = (String) Utils.getConfProperty(Constants.DB_INIT_SCRIPT_FILENAME_KEY);
        } catch (IOException e) {
            e.printStackTrace();
        }
        String schemaFile = null;
        if (platform.equalsIgnoreCase("mysql")) {
            schemaFile = "schema-mysql.sql";
        }else if (platform.equalsIgnoreCase("postgresql")) {
            schemaFile = "schema-postgresql.sql";
        }
        if (script == null || schemaFile == null) {
            LOGGER.error("Database|Schema initialization file not specified.");
            return;
        }
        schemaFile = Utils.getAppHome() + File.separator + "conf" + File.separator + schemaFile;
        File dir = new File(Utils.getAppHome() + File.separator + "bin");
        LOGGER.info("Executing: {} {} {} {}", script, platform, tenant, schemaFile);
        int exitCode = ScriptUtils.execute(dir,"/bin/bash", script, platform, tenant, schemaFile, user, password);
        if (exitCode == 0) {
            LOGGER.info("Database initialized successfully for tenant = {}", tenant);
        }else {
            LOGGER.info("Database initialization failed for tenant = {} with exitCode = {}", tenant,exitCode);
        }

    }

}


