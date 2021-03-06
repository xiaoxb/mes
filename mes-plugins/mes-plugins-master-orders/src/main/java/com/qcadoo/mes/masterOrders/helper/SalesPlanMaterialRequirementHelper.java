package com.qcadoo.mes.masterOrders.helper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.qcadoo.mes.basic.constants.ProductFamilyElementType;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.deliveries.constants.CompanyProductFields;
import com.qcadoo.mes.deliveries.constants.DeliveriesConstants;
import com.qcadoo.mes.masterOrders.constants.MasterOrdersConstants;
import com.qcadoo.mes.masterOrders.constants.SalesPlanFields;
import com.qcadoo.mes.masterOrders.constants.SalesPlanMaterialRequirementFields;
import com.qcadoo.mes.masterOrders.constants.SalesPlanMaterialRequirementProductFields;
import com.qcadoo.mes.masterOrders.constants.SalesPlanProductFields;
import com.qcadoo.mes.materialFlowResources.MaterialFlowResourcesService;
import com.qcadoo.mes.materialFlowResources.constants.MaterialFlowResourcesConstants;
import com.qcadoo.mes.materialFlowResources.constants.ResourceStockDtoFields;
import com.qcadoo.mes.technologies.ProductQuantitiesService;
import com.qcadoo.mes.technologies.constants.MrpAlgorithm;
import com.qcadoo.mes.technologies.constants.ProductBySizeGroupFields;
import com.qcadoo.mes.technologies.constants.TechnologiesConstants;
import com.qcadoo.mes.technologies.dto.OperationProductComponentHolder;
import com.qcadoo.mes.technologies.tree.ProductStructureTreeService;
import com.qcadoo.model.api.BigDecimalUtils;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.model.api.search.JoinType;
import com.qcadoo.model.api.search.SearchRestrictions;

@Service
public class SalesPlanMaterialRequirementHelper {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberService numberService;

    @Autowired
    private MaterialFlowResourcesService materialFlowResourcesService;

    @Autowired
    private ProductQuantitiesService productQuantitiesService;

    @Autowired
    private ProductStructureTreeService productStructureTreeService;

    public List<Entity> generateSalesPlanMaterialRequirementProducts(final Entity salesPlanMaterialRequirement) {
        List<Entity> salesPlanMaterialRequirementProducts = Lists.newArrayList();

        Entity salesPlan = salesPlanMaterialRequirement.getBelongsToField(SalesPlanMaterialRequirementFields.SALES_PLAN);

        createSalesPlanMaterialRequirementProducts(salesPlanMaterialRequirementProducts, salesPlan);

        updateSalesPlanMaterialRequirementProducts(salesPlanMaterialRequirementProducts);

        return salesPlanMaterialRequirementProducts;
    }

    private void createSalesPlanMaterialRequirementProducts(final List<Entity> salesPlanMaterialRequirementProducts,
            final Entity salesPlan) {
        List<Entity> salesPlanProducts = salesPlan.getHasManyField(SalesPlanFields.PRODUCTS);

        for (Entity salesPlanProduct : salesPlanProducts) {
            Entity technology = getTechnology(salesPlanProduct);
            BigDecimal plannedQuantity = salesPlanProduct.getDecimalField(SalesPlanProductFields.PLANNED_QUANTITY);

            if (Objects.nonNull(technology)) {
                Map<OperationProductComponentHolder, BigDecimal> neededQuantities = productQuantitiesService
                        .getNeededProductQuantitiesByOPC(technology, plannedQuantity, MrpAlgorithm.ONLY_COMPONENTS);

                for (Map.Entry<OperationProductComponentHolder, BigDecimal> neededProductQuantity : neededQuantities.entrySet()) {
                    Long productId = neededProductQuantity.getKey().getProductId();
                    Long operationProductComponentId = neededProductQuantity.getKey().getOperationProductComponentId();
                    Entity product = neededProductQuantity.getKey().getProduct();
                    BigDecimal neededQuantity = neededProductQuantity.getValue();

                    if (Objects.isNull(productId)) {
                        List<Entity> productBySizeGroups = getProductBySizeGroups(operationProductComponentId);

                        for (Entity productBySizeGroup : productBySizeGroups) {
                            Entity salesPlanMaterialRequirementProduct = createSalesPlanMaterialRequirementProductFromProductBySizeGroup(
                                    salesPlanMaterialRequirementProducts, productBySizeGroup, neededQuantity);

                            salesPlanMaterialRequirementProducts.add(salesPlanMaterialRequirementProduct);
                        }
                    } else {
                        Entity salesPlanMaterialRequirementProduct = createSalesPlanMaterialRequirementProductFromProduct(
                                salesPlanMaterialRequirementProducts, product, neededQuantity);

                        salesPlanMaterialRequirementProducts.add(salesPlanMaterialRequirementProduct);
                    }
                }
            }
        }
    }

    private Entity getTechnology(final Entity salesPlanProduct) {
        Entity technology = salesPlanProduct.getBelongsToField(SalesPlanProductFields.TECHNOLOGY);

        if (Objects.isNull(technology)) {
            Entity product = salesPlanProduct.getBelongsToField(SalesPlanProductFields.PRODUCT);

            if (ProductFamilyElementType.PRODUCTS_FAMILY.getStringValue()
                    .equals(product.getStringField(ProductFields.ENTITY_TYPE))) {
                Entity parent = product.getBelongsToField(ProductFields.PARENT);

                if (Objects.nonNull(parent)) {
                    technology = productStructureTreeService.findTechnologyForProduct(parent);
                }
            }
        }

        return technology;
    }

    private List<Entity> getProductBySizeGroups(final Long operationProductComponentId) {
        return getProductBySizeGroupDD().find()
                .createAlias(ProductBySizeGroupFields.OPERATION_PRODUCT_IN_COMPONENT,
                        ProductBySizeGroupFields.OPERATION_PRODUCT_IN_COMPONENT, JoinType.LEFT)
                .add(SearchRestrictions.eq(ProductBySizeGroupFields.OPERATION_PRODUCT_IN_COMPONENT + "." + "id",
                        operationProductComponentId))
                .list().getEntities();
    }

    private Entity createSalesPlanMaterialRequirementProductFromProduct(final List<Entity> salesPlanMaterialRequirementProducts,
            final Entity product, final BigDecimal neededQuantity) {
        Optional<Entity> mayBeSalesPlanMaterialRequirementProduct = salesPlanMaterialRequirementProducts.stream()
                .filter(salesPlanMaterialRequirementProduct -> filterByProduct(salesPlanMaterialRequirementProduct, product))
                .findFirst();

        Entity salesPlanMaterialRequirementProduct;

        if (mayBeSalesPlanMaterialRequirementProduct.isPresent()) {
            salesPlanMaterialRequirementProduct = mayBeSalesPlanMaterialRequirementProduct.get();

            BigDecimal quantity = salesPlanMaterialRequirementProduct
                    .getDecimalField(SalesPlanMaterialRequirementProductFields.QUANTITY);

            quantity = quantity.add(neededQuantity, numberService.getMathContext());

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.QUANTITY, quantity);
        } else {
            salesPlanMaterialRequirementProduct = getSalesPlanMaterialRequirementProductDD().create();

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.PRODUCT, product);
            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.QUANTITY, neededQuantity);

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.IS_DELIVERY_CREATED, false);
        }
        return salesPlanMaterialRequirementProduct;
    }

    private Entity createSalesPlanMaterialRequirementProductFromProductBySizeGroup(
            final List<Entity> salesPlanMaterialRequirementProducts, final Entity productBySizeGroup,
            final BigDecimal neededQuantity) {
        Entity product = productBySizeGroup.getBelongsToField(ProductBySizeGroupFields.PRODUCT);
        Entity sizeGroup = productBySizeGroup.getBelongsToField(ProductBySizeGroupFields.SIZE_GROUP);

        Optional<Entity> mayBeSalesPlanMaterialRequirementProduct = salesPlanMaterialRequirementProducts.stream()
                .filter(salesPlanMaterialRequirementProduct -> filterByProduct(salesPlanMaterialRequirementProduct, product)
                        && filterBySizeGroup(salesPlanMaterialRequirementProduct, sizeGroup))
                .findFirst();

        Entity salesPlanMaterialRequirementProduct;

        if (mayBeSalesPlanMaterialRequirementProduct.isPresent()) {
            salesPlanMaterialRequirementProduct = mayBeSalesPlanMaterialRequirementProduct.get();

            BigDecimal sumForSizes = salesPlanMaterialRequirementProduct
                    .getDecimalField(SalesPlanMaterialRequirementProductFields.SUM_FOR_SIZES);

            sumForSizes = sumForSizes.add(neededQuantity, numberService.getMathContext());

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.QUANTITY, sumForSizes);
        } else {
            salesPlanMaterialRequirementProduct = getSalesPlanMaterialRequirementProductDD().create();

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.PRODUCT, product);
            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.SIZE_GROUP, sizeGroup);
            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.SUM_FOR_SIZES, neededQuantity);

            salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.IS_DELIVERY_CREATED, false);
        }

        return salesPlanMaterialRequirementProduct;
    }

    private boolean filterByProduct(final Entity salesPlanMaterialRequirementProduct, final Entity product) {
        return salesPlanMaterialRequirementProduct.getBelongsToField(SalesPlanMaterialRequirementProductFields.PRODUCT).getId()
                .equals(product.getId());
    }

    private boolean filterBySizeGroup(final Entity salesPlanMaterialRequirementProduct, final Entity sizeGroup) {
        return salesPlanMaterialRequirementProduct.getBelongsToField(SalesPlanMaterialRequirementProductFields.SIZE_GROUP).getId()
                .equals(sizeGroup.getId());
    }

    private void updateSalesPlanMaterialRequirementProducts(final List<Entity> salesPlanMaterialRequirementProducts) {
        Set<Long> productIds = getSalesPlanMaterialRequirementProductIds(salesPlanMaterialRequirementProducts);

        if (!productIds.isEmpty()) {
            List<Entity> resourceStocks = getResourceStocks(productIds.stream().map(Long::intValue).collect(Collectors.toSet()));
            List<Entity> companyProducts = getCompanyProducts(productIds);
            Map<Long, BigDecimal> neededQuantitiesFromOrders = getNeededQuantitiesFromOrders(productIds);

            for (Entity salesPlanMaterialRequirementProduct : salesPlanMaterialRequirementProducts) {
                Entity product = salesPlanMaterialRequirementProduct
                        .getBelongsToField(SalesPlanMaterialRequirementProductFields.PRODUCT);

                Long productId = product.getId();

                BigDecimal currentStock = getCurrentStock(resourceStocks, productId);

                Optional<Entity> mayBeCompanyProduct = getCompanyProduct(companyProducts, productId);

                if (mayBeCompanyProduct.isPresent()) {
                    Entity companyProduct = mayBeCompanyProduct.get();

                    Entity supplier = companyProduct.getBelongsToField(CompanyProductFields.COMPANY);
                    BigDecimal minimumOrderQuantity = companyProduct.getDecimalField(CompanyProductFields.MINIMUM_ORDER_QUANTITY);

                    salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.SUPPLIER, supplier);
                    salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.MINIMUM_ORDER_QUANTITY,
                            minimumOrderQuantity);
                }

                BigDecimal neededQuantity = neededQuantitiesFromOrders.get(productId);

                salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.CURRENT_STOCK,
                        currentStock);
                salesPlanMaterialRequirementProduct.setField(SalesPlanMaterialRequirementProductFields.NEEDED_QUANTITY,
                        neededQuantity);
            }
        }
    }

    private Set<Long> getSalesPlanMaterialRequirementProductIds(final List<Entity> salesPlanMaterialRequirementProducts) {
        return salesPlanMaterialRequirementProducts.stream()
                .map(salesPlanMaterialRequirementProduct -> salesPlanMaterialRequirementProduct
                        .getBelongsToField(SalesPlanMaterialRequirementProductFields.PRODUCT).getId())
                .collect(Collectors.toSet());
    }

    private List<Entity> getResourceStocks(final Set<Integer> productIds) {
        return getResourceStockDtoDD().find().add(SearchRestrictions.in(ResourceStockDtoFields.PRODUCT_ID, productIds)).list()
                .getEntities();
    }

    private BigDecimal getCurrentStock(final List<Entity> resourceStocks, final Long productId) {
        BigDecimal currentStock = BigDecimal.ZERO;

        Optional<Entity> mayBeResourceStock = resourceStocks.stream().filter(
                resourceStock -> resourceStock.getIntegerField(ResourceStockDtoFields.PRODUCT_ID).equals(productId.intValue()))
                .findAny();

        if (mayBeResourceStock.isPresent()) {
            Entity resourceStock = mayBeResourceStock.get();

            currentStock = resourceStock.getDecimalField(ResourceStockDtoFields.AVAILABLE_QUANTITY);
        }

        return currentStock;
    }

    private List<Entity> getCompanyProducts(final Set<Long> productIds) {
        return getCompanyProductDD().find().createAlias(CompanyProductFields.PRODUCT, CompanyProductFields.PRODUCT, JoinType.LEFT)
                .add(SearchRestrictions.in(CompanyProductFields.PRODUCT + "." + "id", productIds))
                .add(SearchRestrictions.eq(CompanyProductFields.IS_DEFAULT, true)).list().getEntities();
    }

    private Optional<Entity> getCompanyProduct(final List<Entity> companyProducts, final Long productId) {
        return companyProducts.stream().filter(
                companyProduct -> companyProduct.getBelongsToField(CompanyProductFields.PRODUCT).getId().equals(productId))
                .findAny();
    }

    private Map<Long, BigDecimal> getNeededQuantitiesFromOrders(final Set<Long> productIds) {
        Map<Long, BigDecimal> neededQuantitiesFromOrders = Maps.newHashMap();

        StringBuilder queryBuilder = new StringBuilder();

        queryBuilder.append("SELECT ");
        queryBuilder.append("productid,  SUM(COALESCE(plannedquantity, 0) - COALESCE(usedquantity, 0)) AS neededQuantity ");
        queryBuilder.append("FROM basicproductioncounting_productioncountingquantitydto ");
        queryBuilder.append("WHERE productid IN (:productIds) ");
        queryBuilder.append("AND orderid IN ( ");
        queryBuilder.append("SELECT id FROM orders_order WHERE state NOT IN ('01pending', '04completed', '05declined', '07abandoned') ");
        queryBuilder.append(") ");
        queryBuilder.append("GROUP BY productid");

        Map<String, Object> params = Maps.newHashMap();

        params.put("productIds", productIds);

        try {
            List<Map<String, Object>> values = jdbcTemplate.queryForList(queryBuilder.toString(), params);

            for (Map<String, Object> value : values) {
                Long productId = Long.valueOf(value.get("productId").toString());
                BigDecimal neededQuantity = BigDecimalUtils.convertNullToZero(value.get("neededQuantity"));

                neededQuantitiesFromOrders.put(productId, neededQuantity);
            }
        } catch (EmptyResultDataAccessException e) {
            return neededQuantitiesFromOrders;
        }

        return neededQuantitiesFromOrders;
    }

    private DataDefinition getSalesPlanMaterialRequirementProductDD() {
        return dataDefinitionService.get(MasterOrdersConstants.PLUGIN_IDENTIFIER,
                MasterOrdersConstants.MODEL_SALES_PLAN_MATERIAL_REQUIREMENT_PRODUCT);
    }

    private DataDefinition getProductBySizeGroupDD() {
        return dataDefinitionService.get(TechnologiesConstants.PLUGIN_IDENTIFIER,
                TechnologiesConstants.MODEL_PRODUCT_BY_SIZE_GROUP);
    }

    private DataDefinition getResourceStockDtoDD() {
        return dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_RESOURCE_STOCK_DTO);
    }

    private DataDefinition getCompanyProductDD() {
        return dataDefinitionService.get(DeliveriesConstants.PLUGIN_IDENTIFIER, DeliveriesConstants.MODEL_COMPANY_PRODUCT);
    }

}
