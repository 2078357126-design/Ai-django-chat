"""Project package.

本机 mysqlclient（MySQLdb）编译产物缺少 libmysqlclient rpath，
因此使用纯 Python 的 PyMySQL 作为 MySQL 驱动。
"""

import pymysql

# Django 的 MySQL 后端要求 mysqlclient >= 2.2.1，这里让 PyMySQL 通过版本检查。
pymysql.version_info = (2, 2, 1, "final", 0)
pymysql.install_as_MySQLdb()
