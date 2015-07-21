package com.qcadoo.mes.cmmsMachineParts.states;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.constants.StaffFields;
import com.qcadoo.mes.cmmsMachineParts.constants.*;
import com.qcadoo.mes.cmmsMachineParts.states.constants.MaintenanceEventStateChangeFields;
import com.qcadoo.mes.states.StateChangeContext;
import com.qcadoo.mes.states.messages.constants.StateMessageType;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.model.api.search.SearchQueryBuilder;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

@Service
public class MaintenanceEventStateValidationService {

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberService numberService;

    public void validationOnInProgress(final StateChangeContext stateChangeContext) {
        Entity event = stateChangeContext.getOwner();
        checkIfPersonReceivingIsSet(event, stateChangeContext);

    }

    public void validationOnEdited(final StateChangeContext stateChangeContext) {

    }

    public void validationOnClosed(final StateChangeContext stateChangeContext) {
        Entity event = stateChangeContext.getOwner();
        checkIfSolutionDescriptionIsSet(event, stateChangeContext);
        checkIfWorkerTimeIsFilled(event, stateChangeContext);

        checkWorkerTimesDeviation(event, stateChangeContext);
    }

    public void validationOnRevoked(final StateChangeContext stateChangeContext) {
        String stateChangeComment = stateChangeContext.getStateChangeEntity().getStringField(MaintenanceEventStateChangeFields.COMMENT);
        if(Strings.isNullOrEmpty(stateChangeComment)){
            stateChangeContext.addValidationError("cmmsMachineParts.maintenanceEvent.state.commentRequired");
        }
    }

    public void validationOnPlanned(final StateChangeContext stateChangeContext) {

    }

    private void checkIfPersonReceivingIsSet(Entity event, StateChangeContext stateChangeContext) {
        if (event.getBelongsToField(MaintenanceEventFields.PERSON_RECEIVING) == null) {
            stateChangeContext.addFieldValidationError(MaintenanceEventFields.PERSON_RECEIVING,
                    "cmmsMachineParts.maintenanceEvent.state.fieldRequired");
            stateChangeContext.addValidationError("cmmsMachineParts.maintenanceEvent.state.workerNotFound");
        }
    }

    private void checkIfSolutionDescriptionIsSet(Entity event, StateChangeContext stateChangeContext) {
        if (StringUtils.isEmpty(event.getStringField(MaintenanceEventFields.SOLUTION_DESCRIPTION))) {
            stateChangeContext.addFieldValidationError(MaintenanceEventFields.SOLUTION_DESCRIPTION,
                    "cmmsMachineParts.maintenanceEvent.state.fieldRequired");
        }
    }

    private void checkIfWorkerTimeIsFilled(Entity event, StateChangeContext stateChangeContext) {
        if (event.getHasManyField(MaintenanceEventFields.STAFF_WORK_TIEMS).isEmpty()) {
            stateChangeContext.addValidationError("cmmsMachineParts.maintenanceEvent.state.noWorkersTimeEntry");
        }
    }

    private void checkWorkerTimesDeviation(Entity event, StateChangeContext stateChangeContext) {

        Optional<Integer> progressTime = getProgressTime(event);
        Optional<BigDecimal> possibleDeviationPercent = getPossibleDeviationFromParameters();
        if (!progressTime.isPresent() || !possibleDeviationPercent.isPresent()
                || MaintenanceEventType.from(event).compareTo(MaintenanceEventType.PROPOSAL) == 0) {
            return;
        }
        Integer possibleDeviation = calculatePossibleDeviation(progressTime.get(), possibleDeviationPercent.get());
        Map<Entity, Integer> groupedWorkTimes = getGroupedStaffWorkTimes(event);
        List<String> workersWithIncorrectTime = Lists.newArrayList();
        for (Map.Entry<Entity, Integer> entry : groupedWorkTimes.entrySet()) {
            Integer diff = entry.getValue() - progressTime.get();
            if (diff > possibleDeviation) {
                workersWithIncorrectTime.add(entry.getKey().getStringField(StaffFields.NAME) + " "
                        + entry.getKey().getStringField(StaffFields.SURNAME));
            }
        }
        if (!workersWithIncorrectTime.isEmpty()) {
            stateChangeContext.addMessage("cmmsMachineParts.maintenanceEvent.state.tooLongWorkersTime", StateMessageType.INFO,
                    false, workersWithIncorrectTime.stream().collect(Collectors.joining(", ")));
        }

    }

    private Integer calculatePossibleDeviation(Integer progressTime, BigDecimal possibleDeviationPercent) {
        BigDecimal percent = possibleDeviationPercent.divide(new BigDecimal(100), numberService.getMathContext());
        BigDecimal possibleDeviation = percent.multiply(new BigDecimal(progressTime));
        return possibleDeviation.intValue();
    }

    private Map<Entity, Integer> getGroupedStaffWorkTimes(Entity event) {
        List<Entity> staffWorkTimes = event.getHasManyField(MaintenanceEventFields.STAFF_WORK_TIMES);
        Function<Entity, Entity> toWorker = entity -> entity.getBelongsToField(StaffWorkTimeFields.WORKER);
        ToIntFunction<Entity> toInt = entity -> entity.getIntegerField(StaffWorkTimeFields.LABOR_TIME);
        Map<Entity, Integer> map = staffWorkTimes.stream().collect(Collectors.groupingBy(toWorker, Collectors.summingInt(toInt)));
        return map;
    }

    private Optional<BigDecimal> getPossibleDeviationFromParameters() {
        return Optional.ofNullable(parameterService.getParameter().getDecimalField(
                ParameterFieldsCMP.POSSIBLE_WORK_TIME_DEVIATION));
    }

    private Optional<Integer> getProgressTime(Entity event) {
        StringBuilder hqlForStart = new StringBuilder();
        hqlForStart.append("select max(dateAndTime) as date from #cmmsMachineParts_maintenanceEventStateChange sc ");
        hqlForStart.append("where sc.maintenanceEvent = :eventId and sc.status = '03successful' ");
        hqlForStart.append("and sc.targetState = '02inProgress'");
        SearchQueryBuilder query = dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_MAINTENANCE_EVENT_STATE_CHANGE).find(hqlForStart.toString());
        query.setLong("eventId", event.getId());
        Date start = query.setMaxResults(1).uniqueResult().getDateField("date");

        StringBuilder hqlForEnd = new StringBuilder();
        hqlForEnd.append("select max(dateAndTime) as date from #cmmsMachineParts_maintenanceEventStateChange sc ");
        hqlForEnd.append("where sc.maintenanceEvent = :eventId and sc.status = '03successful' ");
        hqlForEnd.append("and sc.targetState = '03edited'");
        query = dataDefinitionService.get(CmmsMachinePartsConstants.PLUGIN_IDENTIFIER,
                CmmsMachinePartsConstants.MODEL_MAINTENANCE_EVENT_STATE_CHANGE).find(hqlForEnd.toString());
        query.setLong("eventId", event.getId());

        Date end = query.setMaxResults(1).uniqueResult().getDateField("date");

        if (start != null && end != null && start.before(end)) {
            Seconds seconds = Seconds.secondsBetween(new DateTime(start), new DateTime(end));
            return Optional.of(Integer.valueOf(seconds.getSeconds()));
        }
        return Optional.empty();
    }
}
