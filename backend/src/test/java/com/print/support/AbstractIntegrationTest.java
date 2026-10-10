package com.print.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.print.file.entity.PrintFile;
import com.print.file.mapper.PrintFileMapper;
import com.print.order.entity.PrintOrder;
import com.print.order.mapper.PrintOrderMapper;
import com.print.payment.mapper.PaymentRecordMapper;
import com.print.task.entity.PrintTask;
import com.print.task.mapper.PrintTaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 集成测试基类：demo 档（H2 内存库 + 内存队列 + Mock 网关，零外部依赖）。
 *
 * <p>每个用例在事务中执行并自动回滚，保证用例间数据隔离；
 * 提供文件 / 订单 / 任务的构造工厂，便于聚焦断言。
 */
@SpringBootTest
@ActiveProfiles("demo")
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected PrintFileMapper fileMapper;
    @Autowired
    protected PrintOrderMapper orderMapper;
    @Autowired
    protected PrintTaskMapper taskMapper;
    @Autowired
    protected PaymentRecordMapper paymentRecordMapper;

    /** 造一个已上传文件（指定解析页数）。 */
    protected PrintFile newFile(Long userId, int pageCount) {
        PrintFile f = new PrintFile();
        f.setUserId(userId);
        f.setOriginalName("test.pdf");
        f.setStoragePath("test/" + System.nanoTime() + ".pdf");
        f.setContentType("application/pdf");
        f.setSizeBytes(1024L);
        f.setPageCount(pageCount);
        f.setStatus(0);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        fileMapper.insert(f);
        return f;
    }

    protected PrintOrder newOrder(Long userId, Long fileId, BigDecimal amount, int payStatus) {
        return newOrder(userId, fileId, amount, payStatus, LocalDateTime.now().plusMinutes(15));
    }

    /** 造一个订单（orderNo 唯一）。 */
    protected PrintOrder newOrder(Long userId, Long fileId, BigDecimal amount, int payStatus,
                                  LocalDateTime expireAt) {
        LocalDateTime now = LocalDateTime.now();
        PrintOrder o = new PrintOrder();
        o.setOrderNo("PT" + System.nanoTime());
        o.setUserId(userId);
        o.setFileId(fileId);
        o.setParamsJson("{\"copies\":1,\"colorMode\":\"MONO\",\"duplexMode\":\"SIMPLEX\",\"paperSize\":\"A4\"}");
        o.setPageCount(1);
        o.setAmount(amount);
        o.setPayStatus(payStatus);
        o.setPrintStatus(0);
        o.setExpireAt(expireAt);
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        orderMapper.insert(o);
        return o;
    }

    /** 造一个打印任务（paramsJson 与 TaskService.TaskParams 快照结构一致）。 */
    protected PrintTask newTask(String orderNo, Long terminalId, int status, int retryCount,
                                LocalDateTime claimedAt) {
        LocalDateTime now = LocalDateTime.now();
        PrintTask t = new PrintTask();
        t.setOrderNo(orderNo);
        t.setTerminalId(terminalId);
        t.setParamsJson("{\"copies\":1,\"colorMode\":\"MONO\",\"duplexMode\":\"SIMPLEX\","
                + "\"paperSize\":\"A4\",\"pageCount\":1,\"fileId\":1,\"fileName\":\"test.pdf\",\"fileSize\":1024}");
        t.setStatus(status);
        t.setRetryCount(retryCount);
        t.setClaimedAt(claimedAt);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);
        return t;
    }

    /** 统计某订单的打印任务数（唯一索引保证 <= 1）。 */
    protected long countTasks(String orderNo) {
        return taskMapper.selectCount(
                new LambdaQueryWrapper<PrintTask>().eq(PrintTask::getOrderNo, orderNo));
    }
}
