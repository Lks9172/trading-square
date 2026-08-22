create table context_migration_probe (
    id integer primary key,
    applied boolean not null
);

insert into context_migration_probe (id, applied) values (1, true);
