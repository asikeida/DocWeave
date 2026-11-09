package me.asikeida.docweave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocWeaveApplication {

    public static void main(String[] args) {
        // 独立子 JVM 复用同一个 JAR，解析模式不创建 Spring 上下文或连接数据库。
        if (args.length > 0 && "--parse-document".equals(args[0])) {
            me.asikeida.docweave.ingestion.parser.ParserMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(DocWeaveApplication.class, args);
    }

}
