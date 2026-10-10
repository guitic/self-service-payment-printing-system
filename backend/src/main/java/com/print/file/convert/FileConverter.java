package com.print.file.convert;

import java.io.File;
import java.io.IOException;

/**
 * 文件转换器抽象（见设计文档风险 R-5）。
 *
 * <p>用于把 Office 等无法直接数页的文档统一转换为 PDF，从而获得精确页数。
 * 实现方需自行保证线程安全；返回的临时 PDF 由调用方负责清理。
 */
public interface FileConverter {

    /** 转换引擎是否可用（探测结果可缓存）。 */
    boolean isAvailable();

    /**
     * 将源文件转换为 PDF。
     *
     * @param source 源文件
     * @return 转换后的临时 PDF 文件（调用方用后需删除）
     * @throws IOException 转换失败或超时
     */
    File convertToPdf(File source) throws IOException;
}
