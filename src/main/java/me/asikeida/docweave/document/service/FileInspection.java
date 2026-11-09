package me.asikeida.docweave.document.service;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import me.asikeida.docweave.document.config.DocumentProperties;
import me.asikeida.docweave.document.domain.DocumentFailure;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 有界流式摘要及格式检查；不提取业务正文，PDF 临时副本在关闭后删除。 */
@Component
public class FileInspection {
    public record Inspected(String name, long size, String mime, String sha256) { }
    private final DocumentProperties properties;
    private final java.util.concurrent.Semaphore validators;
    public FileInspection(DocumentProperties properties) { this.properties=properties; validators=new java.util.concurrent.Semaphore(properties.maxConcurrent()); }
    public Inspected inspect(MultipartFile file) {
        String name=file.getOriginalFilename();
        if (name==null || name.isBlank() || name.length()>160 || name.chars().anyMatch(c->c<32 || c==127)
                || name.contains("/") || name.contains("\\") || name.contains(":")) fail("INVALID_FILENAME","文件名不合法",400);
        long size=file.getSize();
        if (size<=0) fail("EMPTY_FILE","不能上传空文件",400);
        if (size>properties.maxFileBytes()) fail("FILE_TOO_LARGE","文件不能超过配置大小",413);
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String mime=switch(extension) { case "pdf" -> "application/pdf"; case "txt" -> "text/plain"; case "md" -> "text/markdown"; default -> ""; };
        if (mime.isEmpty()) fail("UNSUPPORTED_FILE_TYPE","只支持 PDF、TXT、Markdown",415);
        String declared=Optional.ofNullable(file.getContentType()).orElse("").split(";",2)[0].toLowerCase(Locale.ROOT);
        if (!Set.of("", "application/octet-stream", mime).contains(declared)
                && !(extension.equals("md") && declared.equals("text/plain"))) fail("UNSUPPORTED_FILE_TYPE","文件类型声明与扩展名不一致",415);
        if(!validators.tryAcquire())throw new DocumentFailure("UPLOAD_RATE_LIMITED","文件校验繁忙，请稍后重试",429);
        Path pdf=null;
        try {
            try(InputStream header=file.getInputStream()) {
                byte[] prefix=header.readNBytes(8);
                boolean pdfMagic=new String(prefix,StandardCharsets.ISO_8859_1).startsWith("%PDF-");
                if(extension.equals("pdf")!=pdfMagic) fail("UNSUPPORTED_FILE_TYPE","文件内容与扩展名不一致",415);
            }
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            long count=0;
            try (InputStream in=file.getInputStream()) {
                byte[] buffer=new byte[16384]; int n;
                while ((n=in.read(buffer))!=-1) { count+=n;if(count>size) fail("INVALID_FILE","文件长度不一致",400);digest.update(buffer,0,n); }
            }
            if(count!=size) fail("INVALID_FILE","文件长度不一致",400);
            if(extension.equals("pdf")) {
                pdf=Files.createTempFile("docweave-pdf-check-",".pdf");
                try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(pdf)){in.transferTo(out);}
                try(var document=Loader.loadPDF(pdf.toFile())) {
                    if(document.isEncrypted()) fail("ENCRYPTED_PDF","暂不支持加密 PDF",400);
                    if(document.getNumberOfPages()<1 || document.getNumberOfPages()>500) fail("PDF_PAGE_LIMIT","PDF 页数须在 1 到 500 页之间",400);
                }
            } else {
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                try(Reader reader=new InputStreamReader(file.getInputStream(),decoder)) {
                    char[] chars=new char[8192];int n;
                    while((n=reader.read(chars))!=-1) for(int i=0;i<n;i++) if(chars[i]==0 || (chars[i]<32 && chars[i]!='\n' && chars[i]!='\r' && chars[i]!='\t')) fail("INVALID_TEXT","文件必须为 UTF-8 文本",415);
                }
            }
            return new Inspected(name,size,mime,HexFormat.of().formatHex(digest.digest()));
        } catch (DocumentFailure e) { throw e; }
        catch (IOException e) { throw new DocumentFailure("INVALID_FILE","文件损坏、加密或编码不受支持",400); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        finally { validators.release(); if(pdf!=null) try { Files.deleteIfExists(pdf); } catch(IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Temporary PDF validation cleanup failed"); } }
    }
    private static void fail(String code,String message,int status) { throw new DocumentFailure(code,message,status); }
}
