package org.evomaster.core.search.service.mutator

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.Lazy
import org.evomaster.core.sql.SqlAction
import org.evomaster.core.problem.externalservice.httpws.service.HarvestActualHttpWsResponseHandler
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.service.RestStructureMutator
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.problem.rest.resource.RestResourceCalls
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.Individual
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.service.*
import org.evomaster.core.search.service.mutator.genemutation.ArchiveGeneMutator
import org.evomaster.core.search.service.mutator.genemutation.ArchiveImpactSelector
import org.evomaster.core.search.tracer.ArchiveMutationTrackService
import org.evomaster.core.search.tracer.TraceableElementCopyFilter
import org.evomaster.core.search.tracer.TrackOperator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import org.evomaster.core.ai.LLM
import org.evomaster.core.logging.LoggingUtil

abstract class Mutator<T> : TrackOperator where T : Individual {

    @Inject
    protected lateinit var randomness: Randomness

    @Inject
    protected lateinit var ff: FitnessFunction<T>

    @Inject
    protected lateinit var time: SearchTimeController

    @Inject
    protected lateinit var apc: AdaptiveParameterControl

    @Inject
    protected lateinit var structureMutator: StructureMutator

    @Inject
    protected lateinit var config: EMConfig

    @Inject
    private lateinit var tracker : ArchiveMutationTrackService

    @Inject
    protected lateinit var archiveGeneSelector : ArchiveImpactSelector

    @Inject
    protected lateinit var archiveGeneMutator : ArchiveGeneMutator

    @Inject
    protected lateinit var mwc : MutationWeightControl

    @Inject
    protected lateinit var harvestResponseHandler: HarvestActualHttpWsResponseHandler

    /**
     * @param mutatedGenes is used to record what genes are mutated within [mutate], which can be further used to analyze impacts of genes.
     * @return a mutated copy
     */
    abstract fun mutate(individual: EvaluatedIndividual<T>, targets: Set<Int> = setOf(), mutatedGenes: MutatedGeneSpecification? = null): T

    /**
     * @param individual an individual to mutate
     * @param evi a reference of the individual to mutate
     * @return a list of genes that are allowed to mutate
     */
    abstract fun genesToMutation(individual: T, evi: EvaluatedIndividual<T>, targets: Set<Int>) : List<Gene>

    /**
     * @param individual an individual to mutate
     * @param evi a reference of the individual to mutate
     * @param targets to cover with this mutation
     * @return a list of genes that are selected to mutate
     */
    abstract fun selectGenesToMutate(individual: T, evi: EvaluatedIndividual<T>, targets: Set<Int> = setOf(), mutatedGenes: MutatedGeneSpecification?) : List<Gene>

    /**
     * @return whether you do a structure mutation
     */
    abstract fun doesStructureMutation(evaluatedIndividual: EvaluatedIndividual<T>) : Boolean

    /**
     * @return whether you do a structure mutation on initialization if it exists
     */
    open fun doesInitStructureMutation(evaluatedIndividual: EvaluatedIndividual<T>): Boolean {
        return config.initStructureMutationProbability > 0 && ((!structureMutator.canApplyActionStructureMutator(evaluatedIndividual.individual))
                || (structureMutator.canApplyInitStructureMutator() && randomness.nextBoolean(config.initStructureMutationProbability)))
    }

    open fun postActionAfterMutation(individual: T, mutated: MutatedGeneSpecification?){}

    open fun update(previous: EvaluatedIndividual<T>, mutated: EvaluatedIndividual<T>, mutatedGenes: MutatedGeneSpecification?, mutationEvaluated: EvaluatedMutation){}

    companion object{
        private val log: Logger = LoggerFactory.getLogger(Mutator::class.java)
    }

    fun getLLMAssistedCodeInfo(targetSite: String, enableLLMAssistedRequestGeneration: Boolean, enableValueExpansion: Boolean): Pair<String?, String?>{
        return LLM.searchForCode(targetSite, enableLLMAssistedRequestGeneration, enableValueExpansion)
    }

    fun LLMAssistedRequestMutate(individual: EvaluatedIndividual<T>, relatedCode: Pair<String?, String?>, hints: MutableList<String>): T?{
        val copy = individual.individual.copy() as T
        var anyLLM = false
        val targetSite = individual.individual.populationOrigin ?: ""

        if(relatedCode.second == null) return null

        var restcallActionInfo = StringBuilder()
        copy.seeMainExecutableActions().forEachIndexed { index, action ->
            val restCallAction = action as? RestCallAction
            if (restCallAction != null) {
                val actionInfo = "Rest Call Action with index: $index is ${restCallAction.getName()}, its body param is ${restCallAction.getBody()}, its query param is ${restCallAction.getQuery()}, its path param is ${restCallAction.getPath()}, its header param is ${restCallAction.getHeader()}, full request is ${restCallAction.toString()}"
                restcallActionInfo.append(actionInfo).append("\n")
            }
        }
        var targetIndex: Int = -1
        var targetParamKey: String = ""
        var targetParamValue: String = ""
        var targetParamType: String = ""
        var retryTimes = 2
        var hint: Map<String, String>? = LLM.generateHintRequest(targetSite, relatedCode, restcallActionInfo.toString(), hints.joinToString(separator = "\n"));
        while(hint == null && retryTimes-->0){
            hint = LLM.generateHintRequest(targetSite, relatedCode, restcallActionInfo.toString(), hints.joinToString(separator = "\n"));
        }
        LoggingUtil.getInfoLogger().info("*Hint is {}", hint)
        if(hint != null){
            targetIndex = hint["index"]?.toIntOrNull() ?: targetIndex
            targetParamKey = hint.getOrDefault("field_name", targetParamKey)
            targetParamValue = hint.getOrDefault("field_value", targetParamValue)
            targetParamType = hint.getOrDefault("param_type", targetParamType)
            var targetAction : RestCallAction? = null
            if(targetIndex >=0 && targetIndex < copy.size()){
                val action = copy.seeMainExecutableActions()[targetIndex]
                if (action is RestCallAction) {
                    targetAction = action
                }
            }

            if(targetAction!=null){
                //LoggingUtil.getInfoLogger().info("*Before edit, Action {} is {}, its body param is {}, its query param is {}, its path param is {}, its header param is {}, full request is {}", targetIndex, targetAction.getName(), targetAction.getBody(), targetAction.getQuery(), targetAction.getPath(), targetAction.getHeader(), targetAction)

                if(targetParamType == "path"){
                    anyLLM = targetAction.setPath(targetParamKey, targetParamValue)
                }
                if(targetParamType == "body"){
                    anyLLM = targetAction.setBody(targetParamValue)
                }
                if(targetParamType == "query"){
                    anyLLM = targetAction.setQuery(targetParamKey, targetParamValue)
                }

                if(targetParamType == "header"){
                    anyLLM = targetAction.setHeader(targetParamKey, targetParamValue)
                }

                if(targetParamType == "path"){
                    //val name = ""
                    //val sampledAction = (structureMutator as RestStructureMutator).sampler.actionCluster[name] as RestCallAction
                    //val pos = copy.seeMainExecutableActions().size
                    //(copy as RestIndividual).addResourceCall(restCalls = RestResourceCalls(actions = mutableListOf(sampledAction), sqlActions = listOf()))

                }

                //LoggingUtil.getInfoLogger().info("*After edit, Action {} is {}, its body param is {}, its query param is {}, its path param is {}, its header param is {}, full request is {}", targetIndex, targetAction.getName(), targetAction.getBody(), targetAction.getQuery(), targetAction.getPath(), targetAction.getHeader(), targetAction)
            }
        } 

        if(anyLLM){
            hints.add(hint.toString())
            return copy
        }
        else return null
    }

    /**
     * @param upToNTimes how many mutations will be applied. can be less if running out of time
     * @param individual which will be mutated
     * @param archive where to save newly mutated individuals (if needed, eg covering new targets)
     */
    fun mutateAndSave(upToNTimes: Int, individual: EvaluatedIndividual<T>, archive: Archive<T>)
            : EvaluatedIndividual<T> {

        if (log.isTraceEnabled){
            log.trace("mutator will be applied, and the individual contains {} dbactions which are",
                individual.individual.seeInitializingActions().size,
                individual.individual.seeInitializingActions().joinToString(","){
                    if (it is SqlAction) it.getResolvedName() else it.getName()
                } )
        }

        var current = individual

        // tracking might be null if the current is never mutated
        preHandlingTrackedIndividual(current)

        val targets = archive.notCoveredTargets().toMutableSet()

        val targetSite = individual.individual.populationOrigin ?: ""
        
        LoggingUtil.getInfoLogger().info("*----------------------")
        LoggingUtil.getInfoLogger().info("*Target is {}", targetSite)

        var relatedCode = getLLMAssistedCodeInfo(targetSite, config.enableLLMAssistedRequestGeneration, config.enableValueExpansion)

        if(relatedCode.first != null){
            //LoggingUtil.getInfoLogger().info("Line code {}", relatedCode.first)
            //LoggingUtil.getInfoLogger().info("Function code\n{}", relatedCode.second)     
            individual.individual.seeMainExecutableActions().forEachIndexed { index, action ->
                LoggingUtil.getInfoLogger().info("*Individual Action {} is {}, its body param is {}, its query param is {}, its path param is {}, its header param is {}, full request is {}", index, (action as RestCallAction).getName(), (action as RestCallAction).getBody(), (action as RestCallAction).getQuery(), (action as RestCallAction).getPath(), (action as RestCallAction).getHeader(), action)
            } 
        }
        var covered = false
        // var anyLLM = false
        var llmTimes = 0
        if(config.enableLLMAssistedRequestGeneration&&relatedCode.first!=null){
            llmTimes = maxOf(2, upToNTimes/2)
        }
        var totalTimes = maxOf(llmTimes, upToNTimes)

        var hints = mutableListOf<String>()
        
        for (i in 0 until totalTimes) {

            if (log.isTraceEnabled){
                log.trace("the individual will be mutated {} times, now it is {}th", upToNTimes, i)
            }

            //save ei (i.e., impact and traces) before its individual is mutated, because the impact info might be updated during mutation
            val currentWithTraces = current.copy(tracker.getCopyFilterForEvalInd(current))

            if (!time.shouldContinueSearch()) {
                break
            }

            val mutatedGenes = MutatedGeneSpecification()

            if (log.isTraceEnabled){
                log.trace("now it is {}th, do addInitializingActions starts", i)
            }
            
            var mutatedIndLLM :T? = null
            if(config.enableLLMAssistedRequestGeneration && !covered && i<llmTimes){
                mutatedIndLLM = LLMAssistedRequestMutate(current, relatedCode, hints)
            }      
            
            if(config.enableLLMAssistedRequestGeneration && covered){
                totalTimes = upToNTimes
            }

            // impact info is updated due to newly added initialization actions
            structureMutator.addInitializingActions(current, mutatedGenes)

            val anyHarvestedExternalServiceActions = structureMutator.addAndHarvestExternalServiceActions(current, mutatedGenes)

            if (log.isTraceEnabled){
                log.trace("now it is {}th, do addInitializingActions ends", i)
            }

            Lazy.assert{current.individual.verifyValidity(); true}

            var mutatedInd: T
            if(mutatedIndLLM!=null){
                mutatedInd = mutatedIndLLM
            }
            else{
                // skip to mutate the individual if any new harvested external actions are added
                mutatedInd = if (!anyHarvestedExternalServiceActions)
                    mutate(current, targets, mutatedGenes)
                else
                    current.individual.copy() as T
            }

            
            // current.individual.seeMainExecutableActions().forEachIndexed { index, action ->
            //     LoggingUtil.getInfoLogger().info("*Current Action {} is {}, its body param is {}, its query param is {}, its path param is {}, its header param is {}, full request is {}", index, (action as RestCallAction).getName(), (action as RestCallAction).getBody(), (action as RestCallAction).getQuery(), (action as RestCallAction).getPath(), (action as RestCallAction).getHeader(), action)
            // }
            if(relatedCode.first != null){
                mutatedInd.seeMainExecutableActions().forEachIndexed { index, action ->
                    LoggingUtil.getInfoLogger().info("*Mutated Action {} is {}, its body param is {}, its query param is {}, its path param is {}, its header param is {}, full request is {}", index, (action as RestCallAction).getName(), (action as RestCallAction).getBody(), (action as RestCallAction).getQuery(), (action as RestCallAction).getPath(), (action as RestCallAction).getHeader(), action)
                }
            }

            mutatedGenes.setMutatedIndividual(mutatedInd)

            Lazy.assert{mutatedInd.verifyValidity(); true}

            //FIXME: why setOf()??? are we skipping coverage collection here???
            // or always added non-covered from archive? if so, name "targets" is confusing
            //Shall we prioritize the targets based on mutation sampling strategy eg, feedbackDirectedSampling?
            val mutated = ff.calculateCoverage(mutatedInd, setOf(), mutatedGenes)
                    ?: continue

            //evaluated mutated by comparing with current using employed targets
            val result = evaluateMutation(mutated, current, targets, archive)

            if (log.isTraceEnabled){
                log.trace("results of evaluateMutation {}", result.value)
            }

            //enable further actions for extracting
            update(currentWithTraces, mutated, mutatedGenes, result)

            //save mutationInfo which is only used for debugging
            archiveGeneMutator.saveMutatedGene(mutatedGenes, index = time.evaluatedIndividuals, individual = mutatedInd, evaluatedMutation = result, targets = targets)
            archive.saveSnapshot()

            val mutatedWithTraces = when{
                config.enableTrackEvaluatedIndividual-> current.next(
                        next = mutated, copyFilter = TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT, evaluatedResult = result)!!
                config.enableTrackIndividual -> {
                    current.nextForIndividual(next = mutated,  evaluatedResult = result)!!
                }
                else -> mutated
            }

            val targetsInfo =
                evaluateMutationInDetails(mutated = mutated, current = current, targets = targets, archive = archive)

            if (config.isEnabledImpactCollection() ){

                /*
                    update impact info regarding targets.
                    To avoid side-effect to impactful gene, remove covered targets
                 */
                mutatedWithTraces.updateImpactOfGenes(previous = currentWithTraces,
                        mutated = mutatedWithTraces, mutatedGenes = mutatedGenes,
                        targetsInfo = targetsInfo.filter { !archive.isCovered(it.key) && !archive.skipTargetForImpactCollection(it.key)}, config)
            }
            /*
                update archive based on mutated individual
                for next, we use [current] that contains latest updated initialization instead of [currentWithTraces]
             */
            current = saveMutation(result, archive, currentWithTraces, mutatedWithTraces)

            
            val targetChosen = archive.lastChosen ?: -1
            
            if(relatedCode.first != null){
                if(!covered)LoggingUtil.getInfoLogger().info("!After, Cover?{}, Target {} {}, LLM-assited {} ", archive.isCovered(targetChosen), targetChosen, individual.individual.populationOrigin, mutatedIndLLM!=null)
                LoggingUtil.getInfoLogger().info("!Results of evaluateMutation {}", result.value)
            }
            covered = covered || archive.isCovered(targetChosen)

            //save impact info which is only used for debugging
            archiveGeneSelector.saveImpactSnapshot(time.evaluatedIndividuals, checkedTargets = targets,targetsInfo = targetsInfo, result = result, evaluatedIndividual = current)

            when(config.mutationTargetsSelectionStrategy){
                EMConfig.MutationTargetsSelectionStrategy.FIRST_NOT_COVERED_TARGET ->{}
                EMConfig.MutationTargetsSelectionStrategy.EXPANDED_UPDATED_NOT_COVERED_TARGET ->{
                    targets.addAll(archive.notCoveredTargets())
                }
                EMConfig.MutationTargetsSelectionStrategy.UPDATED_NOT_COVERED_TARGET ->{
                    targets.clear()
                    targets.addAll(archive.notCoveredTargets())
                }
            }

            if (log.isTraceEnabled){
                log.trace("{}th mutation ends", i)
            }
        }
        LoggingUtil.getInfoLogger().info("*----------------------")
        return current
    }

    fun mutateAndSave(individual: EvaluatedIndividual<T>, archive: Archive<T>)
            : EvaluatedIndividual<T>? {

        structureMutator.addInitializingActions(individual,null)

        return ff.calculateCoverage(mutate(individual), setOf(), null)
                ?.also { archive.addIfNeeded(it) }
    }

    /**
     * @return a result by comparing mutated individual [mutated] with before [current] regarding [targets].
     */
    fun evaluateMutation(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>, targets: Set<Int>, archive: Archive<T>): EvaluatedMutation {
        if (log.isTraceEnabled){
            log.trace("evaluateMutation with the targets {}", targets.joinToString(","){it.toString() })
        }

        // global check
        if (archive.wouldReachNewTarget(mutated)){

            if (log.isTraceEnabled){
                log.trace("archive reach new targets")
            }
            return EvaluatedMutation.BETTER_THAN
        }

        /*
            to compare mutated with current,
            targets for this comparision, employ targets to evaluate individual (i.e., targets in their fitness) can lead to different results.

            e.g., A1 is mutated to A2 by manipulating gene [a], and gene [a] affects target Ta
            1) fitness of A1 includes heuristic for Tb, Tc, fitness of A2 includes heuristic for Ta
         */
        return compare(mutated, current, targets)
    }

    /**
     * @return a result by comparing mutated individual [mutated] with before [current] regarding [targets].
     */
    private fun evaluateMutationInDetails(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>, targets: Set<Int>, archive: Archive<T>): Map<Int, EvaluatedMutation> {

        if (!config.isEnabledImpactCollection() && !config.isEnabledArchiveSolution()) return emptyMap()

        val evaluatedTargets = targets.map { it to EvaluatedMutation.UNSURE }.toMap().toMutableMap()

        // in terms of Archive
        archive.identifyNewTargets(mutated, evaluatedTargets)

        // compare with current
        current.fitness.computeDifference(mutated.fitness, targetSubset = targets, targetInfo = evaluatedTargets, config = config)

        return evaluatedTargets
    }

    private fun compare(mutated: EvaluatedIndividual<T>, current: EvaluatedIndividual<T>, targets: Set<Int>): EvaluatedMutation {

        // current is better than mutated
        val beforeBetter = current.fitness.subsumes(other = mutated.fitness, targetSubset = targets, config = config)
        if (beforeBetter) return EvaluatedMutation.WORSE_THAN
        if (mutated.fitness.subsumes(current.fitness, targets, config)) return EvaluatedMutation.BETTER_THAN
        return EvaluatedMutation.EQUAL_WITH
    }

    private fun preHandlingTrackedIndividual(current: EvaluatedIndividual<T>){
        if (config.trackingEnabled()){
            if (config.enableTrackEvaluatedIndividual && current.tracking == null){
                current.wrapWithTracking(null, config.maxLengthOfTraces, mutableListOf())
                current.pushLatest(current.copy(TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT))
            }
            if (config.enableTrackIndividual && current.individual.tracking == null){
                current.individual.wrapWithTracking(null, config.maxLengthOfTraces, mutableListOf())
                current.individual.pushLatest(current.copy(TraceableElementCopyFilter.WITH_ONLY_EVALUATED_RESULT))
            }
        }
    }


    fun saveMutation(evaluatedMutation: EvaluatedMutation, archive: Archive<T>, current: EvaluatedIndividual<T>, mutated: EvaluatedIndividual<T>) : EvaluatedIndividual<T>{
        // if mutated is not worse than current, we employ the mutated for next mutation
        if (log.isTraceEnabled){
            log.trace("mutation is effective? {}", evaluatedMutation.isEffective())
        }
        if (evaluatedMutation.isEffective()){
            /*
                we only attempt to add individual into archive when it is not worse than current
             */
            archive.addIfNeeded(mutated)
            return mutated
        }
        return current
    }


}