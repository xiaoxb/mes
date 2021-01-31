/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.technologies.listeners;

import java.math.BigDecimal;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.base.Optional;
import com.qcadoo.commons.functional.Either;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.basic.constants.UnitConversionItemFieldsB;
import com.qcadoo.mes.technologies.constants.OperationProductInComponentFields;
import com.qcadoo.model.api.BigDecimalUtils;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.units.PossibleUnitConversions;
import com.qcadoo.model.api.units.UnitConversionService;
import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FieldComponent;
import com.qcadoo.view.api.components.FormComponent;
import com.qcadoo.view.constants.QcadooViewConstants;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.ValidationResult;

@Service
public class OPICDetailsListeners {

    @Autowired
    private UnitConversionService unitConversionService;

    @Autowired
    private NumberService numberService;

    public void calculateQuantityFormula(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        FormComponent operationProductInComponentForm = (FormComponent) view.getComponentByReference(QcadooViewConstants.L_FORM);

        Entity operationProductInComponent = operationProductInComponentForm.getPersistedEntityWithIncludedFormValues();

        String formula = operationProductInComponent.getStringField(OperationProductInComponentFields.QUANTITY_FORMULA);

        formula = formula.replace(",", ".");

        if (StringUtils.isNotBlank(formula)) {
            try {
                Expression expression = new ExpressionBuilder(formula).build();
                ValidationResult result = expression.validate();

                if (result.isValid()) {
                    BigDecimal value = new BigDecimal(expression.evaluate());

                    value = numberService.setScaleWithDefaultMathContext(value);

                    operationProductInComponent.setField(OperationProductInComponentFields.GIVEN_QUANTITY, value);

                    Entity product = operationProductInComponent.getBelongsToField(OperationProductInComponentFields.PRODUCT);
                    String givenUnit = operationProductInComponent.getStringField(OperationProductInComponentFields.GIVEN_UNIT);

                    BigDecimal givenQuantity = value;

                    String baseUnit = product.getStringField(ProductFields.UNIT);

                    if (baseUnit.equals(givenUnit)) {
                        operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, givenQuantity);
                    } else {
                        PossibleUnitConversions unitConversions = unitConversionService.getPossibleConversions(givenUnit,
                                searchCriteriaBuilder -> searchCriteriaBuilder
                                        .add(SearchRestrictions.belongsTo(UnitConversionItemFieldsB.PRODUCT, product)));

                        if (unitConversions.isDefinedFor(baseUnit)) {
                            BigDecimal convertedQuantity = unitConversions.convertTo(givenQuantity, baseUnit);

                            operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, convertedQuantity);
                        } else {
                            operationProductInComponent.addError(
                                    operationProductInComponent.getDataDefinition()
                                            .getField(OperationProductInComponentFields.GIVEN_QUANTITY),
                                    "technologies.operationProductInComponent.validate.error.missingUnitConversion");

                            operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, null);
                        }
                    }

                    operationProductInComponentForm.setEntity(operationProductInComponent);
                } else {
                    operationProductInComponent.addError(
                            operationProductInComponent.getDataDefinition()
                                    .getField(OperationProductInComponentFields.QUANTITY_FORMULA),
                            "technologies.operationProductInComponent.validate.error.badFormula");
                }
            } catch (Exception ex) {
                operationProductInComponent.addError(
                        operationProductInComponent.getDataDefinition()
                                .getField(OperationProductInComponentFields.QUANTITY_FORMULA),
                        "technologies.operationProductInComponent.validate.error.badFormula");
            } finally {
                operationProductInComponentForm.setEntity(operationProductInComponent);
            }
        }
    }

    public void calculateQuantity(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        FormComponent operationProductInComponentForm = (FormComponent) view.getComponentByReference(QcadooViewConstants.L_FORM);

        Entity operationProductInComponent = operationProductInComponentForm.getPersistedEntityWithIncludedFormValues();

        calculateQuantity(view, operationProductInComponent);

        operationProductInComponentForm.setEntity(operationProductInComponent);
    }

    private void calculateQuantity(final ViewDefinitionState view, final Entity operationProductInComponent) {
        Entity technologyInputProductType = operationProductInComponent
                .getBelongsToField(OperationProductInComponentFields.TECHNOLOGY_INPUT_PRODUCT_TYPE);
        Entity product = operationProductInComponent.getBelongsToField(OperationProductInComponentFields.PRODUCT);
        String givenUnit = operationProductInComponent.getStringField(OperationProductInComponentFields.GIVEN_UNIT);

        FieldComponent unitField = (FieldComponent) view.getComponentByReference(OperationProductInComponentFields.UNIT);
        FieldComponent givenQuantityField = (FieldComponent) view
                .getComponentByReference(OperationProductInComponentFields.GIVEN_QUANTITY);

        if ((Objects.isNull(technologyInputProductType) && Objects.isNull(product)) || Objects.isNull(givenUnit)
                || givenUnit.isEmpty() || Objects.isNull(givenQuantityField.getFieldValue())) {
            return;
        }

        Either<Exception, Optional<BigDecimal>> maybeQuantity = BigDecimalUtils
                .tryParse((String) givenQuantityField.getFieldValue(), view.getLocale());

        if (maybeQuantity.isRight()) {
            if (maybeQuantity.getRight().isPresent()) {
                BigDecimal givenQuantity = maybeQuantity.getRight().get();

                if (Objects.nonNull(product)) {
                    String baseUnit = product.getStringField(ProductFields.UNIT);

                    if (baseUnit.equals(givenUnit)) {
                        operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, givenQuantity);
                    } else {
                        PossibleUnitConversions unitConversions = unitConversionService.getPossibleConversions(givenUnit,
                                searchCriteriaBuilder -> searchCriteriaBuilder
                                        .add(SearchRestrictions.belongsTo(UnitConversionItemFieldsB.PRODUCT, product)));

                        if (unitConversions.isDefinedFor(baseUnit)) {
                            BigDecimal convertedQuantity = unitConversions.convertTo(givenQuantity, baseUnit);

                            operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, convertedQuantity);
                        } else {
                            operationProductInComponent.addError(
                                    operationProductInComponent.getDataDefinition()
                                            .getField(OperationProductInComponentFields.GIVEN_QUANTITY),
                                    "technologies.operationProductInComponent.validate.error.missingUnitConversion");

                            operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, null);
                        }
                    }
                } else {
                    operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, givenQuantity);
                    operationProductInComponent.setField(OperationProductInComponentFields.UNIT, givenUnit);

                    unitField.setFieldValue(givenUnit);
                    unitField.requestComponentUpdateState();
                }
            } else {
                operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, null);
            }
        } else {
            operationProductInComponent.setField(OperationProductInComponentFields.QUANTITY, null);
        }
    }

}
