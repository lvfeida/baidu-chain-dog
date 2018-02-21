package site.acsi.baidu.dog.task;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Doubles;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import site.acsi.baidu.dog.config.GlobalConfig;
import site.acsi.baidu.dog.global.DoneOrderSet;
import site.acsi.baidu.dog.invoke.DogMarketInvoke;
import site.acsi.baidu.dog.pojo.*;
import site.acsi.baidu.dog.service.PetOperationService;
import site.acsi.baidu.dog.vo.MarketListResponse;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Acsi
 * @date 2018/2/5
 */
@Component
@Slf4j
public class BuyTask {
    @Resource
    private PetOperationService service;

    @Resource
    private DoneOrderSet doneOrderSet;

    @Resource
    private GlobalConfig config;

    @Resource
    private DogMarketInvoke dogMarketInvoke;

    private Map<Integer, Amount> rareDegreeMap = new ConcurrentHashMap<>(6);


    private static final int FIREST_PAGE = 1;
    private static final String BLANK = " ";
    private static final String COMMA_SEPARATEOR = "，";
    private static final int FREE_PRICE = 0;

    public void initTask() {
        rareDegreeMap.clear();
        config.getConfig().getAmounts().forEach((amount -> rareDegreeMap.put(amount.getRareDegree(), amount)));
    }

    @Async
    @SneakyThrows
    public void doTask(Acount acount) {
        int currPage = FIREST_PAGE;
        long startTime = config.getConfig().getStartTime();
        while (true) {
            if (startTime != config.getConfig().getStartTime() || !config.getConfig().getIsExecutable()) {
                break;
            }

            Thread.sleep(config.getConfig().getTime());
            try {
                // 查询宠物市场
                MarketListResponse response = dogMarketInvoke.list().execute().body();
                Preconditions.checkNotNull(response, "中心服务器宠物市场返回数据为空");
                List<Pet> pets = response.getPets();
                // 日志
                if (config.getConfig().getLogSwitch()) {
                    pageLog(currPage, pets, acount.getDes());
                }
                tryCreateOrder(acount, pets);
                currPage ++;
                if (pets.isEmpty()) {
                    currPage = FIREST_PAGE;
                }
            } catch (Throwable e) {
                if (config.getConfig().getLogSwitch()) {
                    log.error("请求宠物市场列表失败，暂停交易, user:{}", acount.getDes(), e);
                }
                Thread.sleep(5000);
            }
        }
    }

    private void tryCreateOrder(Acount acount, List<Pet> pets) {
        for (Pet pet : pets) {
            try {
                Amount rareDegree = rareDegreeMap.get(pet.getRareDegree());
                Double amount = Doubles.tryParse(pet.getAmount());
                if (canCreateOrder(rareDegree, amount, pet)) {
                    createOrder(acount, pet);
                }
            } catch (Throwable e) {
                if (config.getConfig().getLogSwitch()) {
                    log.error("生单时发生异常, user:{}", acount.getDes(), e);
                }
                log.info("生单时发生异常, user:{} petId:{}，amount:{}", acount.getDes(), pet.getPetId(), pet.getAmount());

            }
        }
    }

    private void pageLog(int currPage, List<Pet> pets, String userName) {
        if (!pets.isEmpty()) {
            StringBuilder info = new StringBuilder();
            for (Pet pet : pets) {
                info.append(rareDegreeMap.get(pet.getRareDegree()).getDes());
                info.append(BLANK);
                info.append(pet.getAmount());
                info.append(COMMA_SEPARATEOR);
            }
            log.info("===  page:{} user:{}，{}", currPage, userName, info);
        }
    }

    private boolean canCreateOrder(Amount rareDegree, Double amount, Pet pet) {
        return null != rareDegree
                && null != amount
                && amount <= rareDegree.getBuyAmount()
                && amount > FREE_PRICE
                && pet.getGeneration() == 0;
    }

    @SneakyThrows
    private void createOrder(Acount acount, Pet item) {
        if (doneOrderSet.isCompleted(item.getPetId())) {
            return;
        }
        log.info("=========================  开始生单 user:{} petid:{} amount:{}", acount.getDes(), item.getPetId(), item.getAmount());
        CreateOrderStatus status = service.createOrder(acount, item.getPetId(), item.getAmount(), item.getValidCode());
        Thread.sleep(15000);
        log.info("===  user:{} success:{} message:{} petid:{} 稀有度:{} amount:{}", acount.getDes(), status.getSuccess(), status.getMessage(), item.getPetId(), rareDegreeMap.get(item.getRareDegree()).getDes(), item.getAmount());
        if (status.getSuccess()) {
            doneOrderSet.add(item.getPetId());
            log.info("******************  success user:{} 稀有度：{} 价格：{} ******************", acount.getDes(), rareDegreeMap.get(item.getRareDegree()).getDes(), item.getAmount());
        }
    }

}