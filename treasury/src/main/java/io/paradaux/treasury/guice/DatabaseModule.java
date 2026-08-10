package io.paradaux.treasury.guice;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import io.paradaux.treasury.mappers.*;
import io.paradaux.treasury.mappers.typehandlers.Sha256BinaryTypeHandler;
import io.paradaux.treasury.mappers.typehandlers.UuidBinaryTypeHandler;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.guice.MyBatisModule;
import org.mybatis.guice.datasource.helper.JdbcHelper;

import javax.sql.DataSource;

public class DatabaseModule extends AbstractModule {

    private final DataSource dataSource;

    public DatabaseModule(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void configure() {
        bind(DataSource.class).toInstance(dataSource);

        install(new MyBatisModule() {
            @Override protected void initialize() {
                install(JdbcHelper.MySQL);

                bindTransactionFactoryType(JdbcTransactionFactory.class);

                environmentId("paper-mybatis");
                bindConstant()
                        .annotatedWith(Names.named("mybatis.configuration.mapUnderscoreToCamelCase"))
                        .to(true);

                addMapperClass(AccountMapper.class);
                addMapperClass(LedgerMapper.class);
                addMapperClass(MembershipMapper.class);
                addMapperClass(GovernmentFineMapper.class);
                addMapperClass(GroupMembershipMapper.class);
                addMapperClass(EconomyPlayerMapper.class);
                addMapperClass(AccountRedirectMapper.class);
                addMapperClass(ChestShopMarketMapper.class);
                addMapperClass(ChestShopSalesReadMapper.class);
                addMapperClass(ShopQueryMapper.class);
                addMapperClass(ExplorerAuditMapper.class);

                addTypeHandlerClass(UuidBinaryTypeHandler.class);
                addTypeHandlerClass(Sha256BinaryTypeHandler.class);
            }
        });
    }
}
