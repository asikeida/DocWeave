package me.asikeida.docweave.sample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import me.asikeida.docweave.auth.service.AuthFailure;

/** 固定的虚构只读模板，不是用户文档记录，不接受调用方提供的文件路径。 */
@Service
public class SampleCatalog {
    public record Summary(String id, String title, String version, String sha256, boolean readyForChat, List<String> suggestedQuestions) { }
    public record Preview(Summary sample, String format, String content) {
        @Override public String toString() { return "Preview[id="+sample.id()+", content=OMITTED]"; }
    }
    private final Map<String, Preview> samples;

    public SampleCatalog() throws IOException {
        samples = Map.of(
                "employee-handbook", load("employee-handbook", "星舟科技员工手册", List.of("试用期员工能申请远程办公吗？", "出差前需要提前多久申请？")),
                "travel-expenses", load("travel-expenses", "星舟科技差旅报销指南", List.of("出差结束后多久内报销？", "住宿每晚600元可以全额报销吗？")));
    }

    public List<Summary> list() { return List.of(samples.get("employee-handbook").sample(), samples.get("travel-expenses").sample()); }
    public Preview preview(String id) {
        Preview sample=samples.get(id);
        if(sample==null) { throw new AuthFailure("SAMPLE_NOT_FOUND", "示例资料不存在", 404); }
        return sample;
    }

    private Preview load(String id, String title, List<String> questions) throws IOException {
        byte[] bytes;
        try(var input=new ClassPathResource("samples/v1/"+id+".md").getInputStream()) { bytes=input.readAllBytes(); }
        // 统一行尾后计算正文摘要，避免Windows/Linux检出方式造成内容版本不一致。
        String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new Preview(new Summary(id,title,"v1",hash,false,List.copyOf(questions)),"plain-text",text);
        } catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable",exception); }
    }
}
