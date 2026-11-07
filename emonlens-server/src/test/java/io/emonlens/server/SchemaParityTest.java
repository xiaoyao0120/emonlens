package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * 两套 schema 基线必须描述同一组表与列。
 *
 * <p>背景：本项目的测试全部跑在 H2（{@code schema-h2.sql}），而 MySQL 部署用的是 {@code sql/emonlens-mysql.sql}。
 * 两份文件各写一遍，于是会漂：曾出现基线缺 {@code evaluator_type} / {@code judge_records_json} /
 * {@code attachments_json} 三列，H2 单测全绿，而新装的 MySQL 库一跑业务就 {@code Unknown column}。
 * 这类问题只能在真库上暴露，代价很高，所以在这里做静态守卫——改表结构时漏改任何一份，这个测试就红。</p>
 *
 * <p>只比表名与列名：类型差异是刻意的（H2 的 TEXT/BOOLEAN 与 MySQL 不同），不作为断言目标。</p>
 */
class SchemaParityTest {

    private static final Path H2_SCHEMA = Paths.get("src/main/resources/schema-h2.sql");
    private static final Path MYSQL_SCHEMA = Paths.get("sql/emonlens-mysql.sql");

    /** 不是列名的行首关键字。 */
    private static final Set<String> NOT_COLUMNS = Set.of(
            "primary", "key", "unique", "index", "constraint", "foreign", "check", "fulltext", "spatial");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?", Pattern.CASE_INSENSITIVE);
    /**
     * 基线里除了 CREATE TABLE，还有用 ALTER 追加的列（H2 基线用 56 条、MySQL 基线用 35 条来对齐迁移），
     * 所以列集合等于「CREATE 块内」+「后续 ALTER 追加」，按文件顺序累积。
     */
    private static final Pattern ALTER_ADD =
            Pattern.compile("ALTER\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?\\s+ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?",
                    Pattern.CASE_INSENSITIVE);
    /** 列定义行：标识符 + 类型（类型至少一个字母开头，如 VARCHAR/TEXT/INT/DECIMAL/BOOLEAN）。 */
    private static final Pattern COLUMN_LINE =
            Pattern.compile("^\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+[A-Za-z]", Pattern.CASE_INSENSITIVE);

    @Test
    void h2AndMysqlBaselinesDefineTheSameTablesAndColumns() throws IOException {
        Map<String, Set<String>> h2 = parse(read(H2_SCHEMA));
        Map<String, Set<String>> mysql = parse(read(MYSQL_SCHEMA));

        assertThat(h2).as("H2 基线应解析出表").isNotEmpty();
        assertThat(mysql).as("MySQL 基线应解析出表").isNotEmpty();

        Set<String> onlyInH2 = new LinkedHashSet<>(h2.keySet());
        onlyInH2.removeAll(mysql.keySet());
        Set<String> onlyInMysql = new LinkedHashSet<>(mysql.keySet());
        onlyInMysql.removeAll(h2.keySet());
        assertThat(onlyInH2).as("只有 H2 基线有的表").isEmpty();
        assertThat(onlyInMysql).as("只有 MySQL 基线有的表").isEmpty();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : h2.entrySet()) {
            Set<String> mysqlColumns = mysql.get(entry.getKey());
            for (String column : entry.getValue()) {
                if (!mysqlColumns.contains(column)) {
                    missing.add(entry.getKey() + "." + column);
                }
            }
        }
        assertThat(missing)
                .as("MySQL 基线缺少这些列（新装库会在运行期报 Unknown column）：%s", missing)
                .isEmpty();
    }

    private String read(Path path) throws IOException {
        Path resolved = Files.exists(path) ? path : Paths.get("..").resolve(path);
        assertThat(Files.exists(resolved)).as("找不到 schema 文件：%s", path.toAbsolutePath()).isTrue();
        return new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
    }

    /**
     * 解析 CREATE TABLE 块内的列名。逐字符跟踪括号深度，避免把 DEFAULT '...' 里的逗号或括号当成结构；
     * 同时跳过 KEY/UNIQUE/CONSTRAINT 等约束行。
     */
    static Map<String, Set<String>> parse(String sql) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            int open = sql.indexOf('(', matcher.end());
            if (open < 0) {
                continue;
            }
            int close = matchingParen(sql, open);
            if (close < 0) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            for (String line : sql.substring(open + 1, close).split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                Matcher column = COLUMN_LINE.matcher(line);
                if (!column.find()) {
                    continue;
                }
                String name = column.group(1).toLowerCase();
                if (NOT_COLUMNS.contains(name)) {
                    continue;
                }
                columns.add(name);
            }
            tables.put(table, columns);
        }
        // ALTER 追加的列：有些基线把迁移里的 ADD COLUMN 直接写成 ALTER，必须一并计入
        Matcher alter = ALTER_ADD.matcher(sql);
        while (alter.find()) {
            String table = alter.group(1).toLowerCase();
            String column = alter.group(2).toLowerCase();
            if (NOT_COLUMNS.contains(column)) {
                continue;
            }
            tables.computeIfAbsent(table, key -> new LinkedHashSet<>()).add(column);
        }
        return tables;
    }

    private static int matchingParen(String sql, int open) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 跳过字符串字面量，避免其中的括号影响配对
                i++;
                while (i < sql.length() && sql.charAt(i) != '\'') {
                    if (sql.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 解析器自身的守卫：两份文件都得解析出接近真实规模的表与列，否则上面的比较就是空转。 */
    @Test
    void parserActuallySeesTheSchema() throws IOException {
        Map<String, Set<String>> h2 = parse(read(H2_SCHEMA));
        int totalColumns = h2.values().stream().mapToInt(Set::size).sum();
        assertThat(h2.size()).as("H2 表数应当与真实基线量级一致").isGreaterThan(60);
        assertThat(totalColumns).as("H2 列总数应当与真实基线量级一致").isGreaterThan(800);
        assertThat(h2.get("et_eval_result"))
                .as("et_eval_result 应解析出列，且包含 judge_records_json")
                .isNotNull()
                .contains("judge_records_json", "workspace_id", "evaluator_key");
    }
}
