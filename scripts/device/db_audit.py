import os
import sqlite3
import sys


def open_snapshot(directory):
    path = os.path.join(directory, "logistics.db")
    if not os.path.isfile(path):
        raise SystemExit(f"缺少数据库副本：{path}")
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def table_names(connection):
    rows = connection.execute(
        "select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name"
    ).fetchall()
    return [row[0] for row in rows]


def table_rows(connection, table):
    return connection.execute(f'select * from "{table}"').fetchall()


def compare(before, after):
    before_tables = set(table_names(before))
    after_tables = set(table_names(after))
    print(f"表：before {len(before_tables)}，after {len(after_tables)}")
    for table in sorted(before_tables | after_tables):
        if table not in before_tables or table not in after_tables:
            print(f"{table}: 只出现在一侧")
            continue
        left = table_rows(before, table)
        right = table_rows(after, table)
        added = [row for row in right if row not in left]
        removed = [row for row in left if row not in right]
        print(f"{table}: before {len(left)}，after {len(right)}，新增 {len(added)}，消失 {len(removed)}")
        for row in added[:2]:
            print(f"  新增示例：{str(row)[:300]}")
        for row in removed[:2]:
            print(f"  消失示例：{str(row)[:300]}")


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法：db_audit.py <before 目录> <after 目录> [更多 after 目录]")
    before = open_snapshot(sys.argv[1])
    try:
        for directory in sys.argv[2:]:
            print(f"== {os.path.basename(directory)} ==")
            after = open_snapshot(directory)
            try:
                compare(before, after)
            finally:
                after.close()
    finally:
        before.close()


if __name__ == "__main__":
    main()
