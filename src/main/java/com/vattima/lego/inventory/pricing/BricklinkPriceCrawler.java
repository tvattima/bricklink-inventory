package com.vattima.lego.inventory.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.PriceGuide;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.bricklink.data.lego.dao.BricklinkInventoryDao;
import net.bricklink.data.lego.dao.BricklinkSaleItemDao;
import net.bricklink.data.lego.dto.BricklinkInventory;
import net.bricklink.data.lego.dto.BricklinkSaleItem;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
@RequiredArgsConstructor
public class BricklinkPriceCrawler {
    private static final Integer ONE = 1;

    private final BricklinkInventoryDao bricklinkInventoryDao;
    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkAjaxClient bricklinkAjaxClient;
    private final BricklinkSaleItemDao bricklinkSaleItemDao;

    private Function<BricklinkInventory, Stream<InventoryWorkHolder>> inventoryWorkHolders = iwh -> Stream.of(
            new InventoryWorkHolder(iwh.getItemType(), "stock", "N", iwh),
            new InventoryWorkHolder(iwh.getItemType(), "sold", "N", iwh),
            new InventoryWorkHolder(iwh.getItemType(), "stock", "U", iwh),
            new InventoryWorkHolder(iwh.getItemType(), "sold", "U", iwh)
    );

    public void crawlPrices() {
        logInventoryItems(updateBricklinkSaleItems(inventoryItems()));
    }

    private Stream<BricklinkInventory> inventoryItems() {
        return bricklinkInventoryDao.getInventoryWork()
                                    .parallelStream()
                                    .filter(BricklinkInventory::shouldSynchronize);
    }

    private List<InventoryWorkHolder> updateBricklinkSaleItems(Stream<BricklinkInventory> bricklinkInventoryStream) {
        return bricklinkInventoryStream.map(bli -> inventoryWorkHolders.apply(bli))
                                       .flatMap(s -> s.parallel()
                                                      .peek(iwh -> {
                                                          if (iwh.getGuideType()
                                                                 .equals("stock")) {
                                                              CatalogItemsForSaleResult catalogNewItemsForSaleResult = bricklinkAjaxClient.catalogItemsForSale(
                                                                      new ParamsBuilder()
                                                                              .of("itemid", iwh
                                                                                      .getBricklinkInventory()
                                                                                      .getBlItemId())
                                                                              .of("cond", iwh.getNewUsed())
                                                                              .of("rpp", 500)
                                                                              .get());
                                                              iwh.setItemsForSale(catalogNewItemsForSaleResult.getList());
                                                              iwh.getItemsForSale()
                                                                 .forEach(ifs -> {
                                                                     BricklinkSaleItem bricklinkSaleItem = iwh.buildBricklinkSaleItem(ifs);
                                                                     try {
                                                                         bricklinkSaleItemDao.upsert(bricklinkSaleItem);
                                                                     } catch (Exception e) {
                                                                         log.error("Could not upsert [" + bricklinkSaleItem + "]", e);
                                                                         e.printStackTrace();
                                                                     }
                                                                 });
                                                              bricklinkSaleItemDao.updateBricklinkSaleItemSold(iwh.getBricklinkInventory()
                                                                                                                  .getBlItemId(), iwh.getNewUsed(), iwh.getCurrentlyForSaleInventoryIds());
                                                          }
                                                          PriceGuide pg = bricklinkRestClient.getPriceGuide(iwh.getType(),
                                                                  iwh.getBricklinkInventory()
                                                                     .getBlItemNo(),
                                                                  new ParamsBuilder()
                                                                          .of("type", iwh.getType())
                                                                          .of("guide_type", iwh.getGuideType())
                                                                          .of("new_or_used", iwh.getNewUsed())
                                                                          .get())
                                                                                             .getData();

                                                          iwh.setPriceGuide(pg);
                                                      })
                                       )
                                       .collect(Collectors.toList());
    }

    private void logInventoryItems(List<InventoryWorkHolder> inventoryWorkHolders) {
        inventoryWorkHolders.parallelStream()
                            .peek(iwh -> {
                                PriceGuide pg = iwh.getPriceGuide();
                                log.info("[{}::#{} Stock/Sold:{} New/Used: {} min:{} avg:{} max:{}]",
                                        iwh.getBricklinkInventory()
                                           .getBlItemId(),
                                        pg.getItem()
                                          .getNo(),
                                        iwh.getGuideType(),
                                        pg.getNew_or_used(),
                                        pg.getMin_price(),
                                        pg.getAvg_price(),
                                        pg.getMax_price());
                            })
                            .collect(Collectors.toList());
    }

    private static class ParamsBuilder {
        private Map<String, Object> params = new HashMap<>();

        public ParamsBuilder of(String key, Object value) {
            params.put(key, value);
            return this;
        }

        public Map<String, Object> get() {
            return params;
        }
    }

    @Setter
    @Getter
    @RequiredArgsConstructor
    @ToString
    private class InventoryWorkHolder {
        private final String type;
        private final String guideType;
        private final String newUsed;
        private final BricklinkInventory bricklinkInventory;
        private PriceGuide priceGuide = new PriceGuide();
        private List<ItemForSale> itemsForSale = new ArrayList<>();

        BricklinkSaleItem buildBricklinkSaleItem(ItemForSale itemForSale) {
            BricklinkSaleItem bricklinkSaleItem = new BricklinkSaleItem();
            bricklinkSaleItem.setBlItemId(getBricklinkInventory().getBlItemId());
            bricklinkSaleItem.setInventoryId(itemForSale.getIdInv());
            bricklinkSaleItem.setCompleteness(itemForSale.getCodeComplete());
            bricklinkSaleItem.setDateCreated(Instant.now());
            bricklinkSaleItem.setDescription(StringUtils.trim(Optional.ofNullable(itemForSale.getStrDesc())
                                                                      .map(d -> d.replaceAll("[^\\x00-\\x7F]", ""))
                                                                      .orElse("")));
            bricklinkSaleItem.setHasExtendedDescription(ONE.equals(itemForSale.getHasExtendedDescription()));
            bricklinkSaleItem.setNewOrUsed(itemForSale.getCodeNew());
            bricklinkSaleItem.setQuantity(itemForSale.getN4Qty());
            bricklinkSaleItem.setUnitPrice(itemForSale.getSalePrice());
            return bricklinkSaleItem;
        }

        public List<Integer> getCurrentlyForSaleInventoryIds() {
            return itemsForSale.stream()
                               .map(ItemForSale::getIdInv)
                               .collect(Collectors.toList());
        }
    }
}