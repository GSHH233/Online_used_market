package org.example.online_used_marketplace.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MySqlDataSourceConfigTest {

    @Test
    void customFactoryUsesConfiguredLoggingMappingAndGlobalOptions() throws Exception {
        MybatisPlusProperties properties = new MybatisPlusProperties();
        properties.setConfiguration(new MybatisPlusProperties.CoreConfiguration()
                .setMapUnderscoreToCamelCase(false).setLogImpl(NoLoggingImpl.class));
        properties.getGlobalConfig().getDbConfig().setLogicDeleteValue("9");
        var configuration = new MySqlDataSourceConfig();
        var factory = configuration.mysqlSqlSessionFactory(mock(DataSource.class), properties,
                configuration.mybatisPlusInterceptor());

        assertThat(factory.getConfiguration().isMapUnderscoreToCamelCase()).isFalse();
        assertThat(factory.getConfiguration().getLogImpl()).isEqualTo(NoLoggingImpl.class);
        assertThat(factory.getConfiguration().getInterceptors()).hasSize(1);
        assertThat(com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils
                .getGlobalConfig(factory.getConfiguration()).getDbConfig().getLogicDeleteValue()).isEqualTo("9");
    }

    @Test
    void mysqlPaginationProducesBoundLimitAndCapsOversizedPages() {
        var config = new MySqlDataSourceConfig();
        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor)
                config.mybatisPlusInterceptor().getInterceptors().get(0);
        var page = new Page<>(2, 10_000, false);
        Map<String, Object> parameters = Map.of("page", page);
        MybatisConfiguration mybatis = new MybatisConfiguration();
        BoundSql sql = new BoundSql(mybatis, "SELECT id FROM pms_product ORDER BY id", List.of(), parameters);
        MappedStatement statement = new MappedStatement.Builder(mybatis, "products.list",
                parameter -> sql, SqlCommandType.SELECT).build();

        pagination.beforeQuery(mock(Executor.class), statement, parameters, RowBounds.DEFAULT, null, sql);

        assertThat(page.getSize()).isEqualTo(100);
        assertThat(sql.getSql()).containsIgnoringCase("LIMIT");
        assertThat(sql.getParameterMappings()).hasSize(2);
    }
}
