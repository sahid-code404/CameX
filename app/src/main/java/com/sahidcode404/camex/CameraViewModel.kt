package com.sahidcode404.camex

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.diagnostics.CameraStartupTraceSnapshot
import com.sahidcode404.camex.core.camera.discovery.HybridCameraDiscoverySnapshot
import com.sahidcode404.camex.core.camera.runtime.ActiveCameraSelection
import com.sahidcode404.camex.core.camera.runtime.CameraRuntimeCoordinator
import com.sahidcode404.camex.core.camera.runtime.CameraRuntimePhase
import com.sahidcode404.camex.core.camera.runtime.CameraSelectionPolicy
import com.sahidcode404.camex.core.camera.topology.CameraDiscoverySource
import com.sahidcode404.camex.core.camera.topology.CameraProfileSelector
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.toLensDescriptor
import com.sahidcode404.camex.core.diagnostics.CompatibilityReportFactory
import com.sahidcode404.camex.core.diagnostics.PlatformDiagnostics
import com.sahidcode404.camex.core.logic.CompatibilityReportJson
import com.sahidcode404.camex.core.logic.LensDuplicateFilter
import com.sahidcode404.camex.core.logic.LensMath
import com.sahidcode404.camex.core.logic.LensPreferenceOrdering
import com.sahidcode404.camex.core.logic.PrimaryLensSelector
import com.sahidcode404.camex.core.model.ActiveCameraSelectionReport
import com.sahidcode404.camex.core.model.CameraUiSelectionReport
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.LensPreferencesState
import com.sahidcode404.camex.core.settings.LensSettingsStore
import com.sahidcode404.camex.feature.camera.CameraScreenUiState
import com.sahidcode404.camex.feature.camera.LensButtonUiModel
import com.sahidcode404.camex.feature.diagnostics.CameraProfileDiagnosticsUiModel
import com.sahidcode404.camex.feature.diagnostics.DiagnosticField
import com.sahidcode404.camex.feature.diagnostics.DiagnosticsUiState
import com.sahidcode404.camex.feature.diagnostics.LensDiagnosticsUiModel
import com.sahidcode404.camex.feature.lenssettings.LensSettingsUiModel
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraAppUiState(
    val camera: CameraScreenUiState = CameraScreenUiState(
        permissionGranted = false,
        statusText = "Camera permission required",
    ),
    val lensSettings: List<LensSettingsUiModel> = emptyList(),
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
)

private data class CameraUiRuntime(
    val sessionState: CameraSessionState,
    val session: CameraRuntimeSnapshot,
    val topology: CameraTopology,
    val discovery: HybridCameraDiscoverySnapshot,
    val phase: CameraRuntimePhase,
    val activeSelection: ActiveCameraSelection? = null,
    val startupTrace: CameraStartupTraceSnapshot = CameraStartupTraceSnapshot(),
) {
    /** One descriptor per canonical optical lens. Profiles never become normal lens buttons. */
    val lenses: List<LensDescriptor>
        get() {
            val canonical = topology.routes.mapIndexed { index, route ->
                route.toLensDescriptor().copy(discoveryOrder = index)
            }
            val active = activeSelection ?: return canonical
            val fingerprint = active.canonicalLensFingerprint ?: return canonical
            if (canonical.any { it.fingerprint?.value == fingerprint.value }) return canonical
            val fallback = active.activeProfileDescriptor ?: return canonical
            return canonical + fallback.copy(
                fingerprint = fingerprint,
                discoveryOrder = canonical.size,
            )
        }

    /** Canonical optical selection derived only from the verified runtime selection model. */
    val selectedLens: LensDescriptor?
        get() {
            val active = activeSelection ?: return null
            val fingerprint = active.canonicalLensFingerprint ?: return null
            return topology.routes.firstOrNull {
                it.lensFingerprint?.value == fingerprint.value
            }?.toLensDescriptor()
                ?: active.activeProfileDescriptor?.copy(fingerprint = fingerprint)
        }
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val runtimeCoordinator = CameraRuntimeCoordinator(appContext, viewModelScope)
    private val controller: CameraSessionController = runtimeCoordinator.session
    private val settingsStore = LensSettingsStore(appContext)
    private val permissionGranted = MutableStateFlow(false)
    private val settings = settingsStore.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LensPreferencesState(),
    )
    private val platform = PlatformDiagnostics.collect(appContext)
    private var discoveryJob: Job? = null
    private var hasStarted = false

    private val runtimeUi = combine(
        controller.state,
        controller.snapshot,
        runtimeCoordinator.topology,
        runtimeCoordinator.discovery.snapshot,
        runtimeCoordinator.phase,
    ) { sessionState, session, topology, discovery, phase ->
        CameraUiRuntime(sessionState, session, topology, discovery, phase)
    }.combine(runtimeCoordinator.activeSelection) { runtime, activeSelection ->
        runtime.copy(activeSelection = activeSelection)
    }.combine(runtimeCoordinator.discovery.startupTrace.snapshot) { runtime, trace ->
        runtime.copy(startupTrace = trace)
    }

    val uiState: StateFlow<CameraAppUiState> = combine(
        permissionGranted,
        runtimeUi,
        settings,
    ) { permission, runtime, preferences ->
        buildUiState(permission, runtime, preferences)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        CameraAppUiState(),
    )

    init {
        launchSafely { runtimeCoordinator.bootstrapCache() }
        launchSafely {
            runtimeCoordinator.activeSelection.collect { selection ->
                if (selection?.verified == true &&
                    (selection.facing == LensFacing.BACK || selection.facing == LensFacing.FRONT)
                ) {
                    selection.canonicalLensFingerprint?.value?.let { fingerprint ->
                        settingsStore.setLastSelected(selection.facing, fingerprint)
                    }
                }
            }
        }
    }

    fun onCameraPermission(granted: Boolean) {
        val changed = permissionGranted.value != granted
        permissionGranted.value = granted
        if (!granted) {
            discoveryJob?.cancel()
            launchSafely { runtimeCoordinator.pause() }
            return
        }
        if ((changed || !hasStarted) && discoveryJob?.isActive != true) {
            startCameraRuntime()
        } else {
            launchSafely { runtimeCoordinator.resume() }
        }
    }

    fun rescanCameras() {
        if (permissionGranted.value) launchSafely { runtimeCoordinator.normalRescan() }
    }

    fun deepRescanCameras() {
        if (permissionGranted.value) launchSafely { runtimeCoordinator.deepRescan() }
    }

    fun resetDiscoveryCache() {
        launchSafely { runtimeCoordinator.resetDiscoveryCache() }
    }

    /** Surface lifecycle never chooses a camera. The controller reuses its exact selected profile. */
    fun bindPreview(view: android.view.TextureView) {
        launchSafely { controller.bindPreview(view) }
    }

    fun unbindPreview() {
        launchSafely { controller.unbindPreview() }
    }

    fun onBackground() {
        discoveryJob?.cancel()
        launchSafely { runtimeCoordinator.pause() }
    }

    fun selectLens(fingerprint: String) {
        val lens = selectorLenses(currentRuntime(), settings.value, includeHidden = false)
            .firstOrNull { it.fingerprint?.value == fingerprint }
            ?: return
        openAndRemember(lens)
    }

    fun switchFacing() {
        val runtime = currentRuntime()
        val active = runtime.activeSelection?.takeIf { it.verified } ?: return
        val preferences = settings.value
        val lenses = selectorLenses(runtime, preferences, includeHidden = false)
        val targetFacing = CameraSelectionPolicy.targetFacing(lenses, active.facing) ?: return
        CameraSelectionPolicy.chooseSwitchTarget(lenses, targetFacing, preferences)
            ?.let(::openAndRemember)
    }

    fun setLensVisible(fingerprint: String, visible: Boolean) {
        launchSafely {
            settingsStore.setVisible(fingerprint, visible)
            val runtimeSnapshot = currentRuntime()
            val selectedFingerprint = runtimeSnapshot.activeSelection
                ?.canonicalLensFingerprint
                ?.value
            val records = settings.value.records.associateBy { it.fingerprint }.toMutableMap()
            records[fingerprint] = (records[fingerprint] ?: LensPreferenceRecord(fingerprint))
                .copy(visible = visible)
            val nextPreferences = settings.value.copy(records = records.values.toList())
            val candidates = selectorLenses(
                runtimeSnapshot,
                nextPreferences,
                includeHidden = false,
            )
            if (visible && selectedFingerprint == null) {
                candidates.firstOrNull { it.fingerprint?.value == fingerprint }
                    ?.let { runtimeCoordinator.selectLens(it) }
                controller.resume()
            } else if (!visible && selectedFingerprint == fingerprint) {
                val activeFacing = runtimeSnapshot.activeSelection?.facing
                val target = candidates.firstOrNull { it.facing == activeFacing }
                    ?: candidates.firstOrNull()
                if (target == null) {
                    controller.pause()
                } else {
                    runtimeCoordinator.selectLens(target)
                }
            }
        }
    }

    fun renameLens(fingerprint: String, label: String?) {
        launchSafely { settingsStore.rename(fingerprint, label) }
    }

    fun moveLens(from: Int, to: Int) {
        val ordered = lensSettingsModels(currentRuntime(), settings.value)
            .map { it.fingerprint }
            .toMutableList()
        if (from !in ordered.indices || to !in ordered.indices || from == to) return
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        launchSafely { settingsStore.setOrder(ordered) }
    }

    fun setOneXReference(fingerprint: String) {
        launchSafely { settingsStore.setOneXReference(fingerprint) }
    }

    fun compatibilityReportJson(): String {
        val runtime = currentRuntime()
        val preferences = settings.value
        val selector = selectorLenses(runtime, preferences, includeHidden = false)
        val projection = CameraSelectionPolicy.project(
            selectorLenses = selector,
            activeSelection = runtime.activeSelection,
            sessionState = runtime.sessionState,
        )
        val base = CompatibilityReportFactory.create(
            context = appContext,
            topology = runtime.topology,
            discovery = runtime.discovery,
            startupTrace = runtime.startupTrace,
            lenses = selector,
        )
        val active = runtime.activeSelection
        return CompatibilityReportJson.encode(
            base.copy(
                activeSelection = active?.let {
                    ActiveCameraSelectionReport(
                        activeProfileRoutingKey = it.activeProfileRoutingKey,
                        activeProfileFingerprint = it.activeProfileFingerprint,
                        canonicalLensFingerprint = it.canonicalLensFingerprint,
                        canonicalLensId = it.canonicalLensId,
                        activeProfileFacing = it.activeProfileDescriptor?.facing ?: LensFacing.UNKNOWN,
                        canonicalFacing = it.facing,
                        selectionGeneration = it.selectionGeneration,
                        sessionState = it.sessionState.javaClass.simpleName,
                        verified = it.verified,
                    )
                },
                cameraUi = CameraUiSelectionReport(
                    normalVisibleFrontCount = selector.count { it.facing == LensFacing.FRONT },
                    normalVisibleRearCount = selector.count { it.facing == LensFacing.BACK },
                    cameraUiFacing = projection.activeFacing,
                    cameraUiLensCount = projection.lenses.size,
                    selectedCanonicalFingerprint = projection.selectedFingerprint,
                    switchFacingTarget = projection.switchTarget,
                    switchFacingEnabled = projection.switchEnabled,
                ),
            ),
        )
    }

    suspend fun writeCompatibilityReport(uri: Uri, json: String) = withContext(Dispatchers.IO) {
        require(json.isNotBlank()) { "Compatibility report is empty" }
        appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.write(json)
        } ?: error("The selected document could not be opened")
    }

    override fun onCleared() {
        runtimeCoordinator.close()
    }

    private fun startCameraRuntime() {
        discoveryJob?.cancel()
        discoveryJob = launchSafely {
            val preferences = settingsStore.state.first()
            runtimeCoordinator.start(
                preferredRearFingerprint = preferences.lastSelectedRearFingerprint,
                oneXReferenceFingerprint = preferences.oneXReferenceFingerprint,
            )
            hasStarted = true
        }
    }

    private fun currentRuntime() = CameraUiRuntime(
        sessionState = controller.state.value,
        session = controller.snapshot.value,
        topology = runtimeCoordinator.topology.value,
        discovery = runtimeCoordinator.discovery.snapshot.value,
        phase = runtimeCoordinator.phase.value,
        activeSelection = runtimeCoordinator.activeSelection.value,
        startupTrace = runtimeCoordinator.discovery.startupTrace.current(),
    )

    private fun openAndRemember(lens: LensDescriptor) {
        launchSafely { runtimeCoordinator.selectLens(lens) }
    }

    private fun launchSafely(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (fatal: ThreadDeath) {
            throw fatal
        } catch (error: Throwable) {
            Log.w("CameraViewModel", "Recoverable operation failed: ${error.javaClass.simpleName}")
        }
    }

    private fun buildUiState(
        permission: Boolean,
        runtime: CameraUiRuntime,
        preferences: LensPreferencesState,
    ): CameraAppUiState {
        val sessionState = runtime.sessionState
        val selector = selectorLenses(runtime, preferences, includeHidden = false)
        val projection = CameraSelectionPolicy.project(
            selectorLenses = selector,
            activeSelection = runtime.activeSelection,
            sessionState = sessionState,
        )
        val reference = referenceLens(
            selectorLenses(runtime, preferences, includeHidden = true),
            preferences,
        )
        val records = preferences.records.associateBy { it.fingerprint }
        val cameraState = CameraScreenUiState(
            permissionGranted = permission,
            statusText = when {
                permission && selector.isEmpty() && runtime.lenses.isNotEmpty() ->
                    "No visible photographic lens. Re-enable one in Lens settings."
                permission && selector.isEmpty() && runtime.phase ==
                    CameraRuntimePhase.ReconcilingTopology ->
                    "Starting camera while discovery continues in the background"
                else -> sessionState.statusText()
            },
            lenses = projection.lenses.map { lens ->
                val fingerprint = lens.fingerprint?.value ?: lens.identity.routingKey
                LensButtonUiModel(
                    fingerprint = fingerprint,
                    label = displayLabel(lens, records[fingerprint]?.displayName, reference),
                    facing = lens.facing.name,
                    enabled = lens.usability.isSelectable && projection.lensSelectionEnabled,
                )
            },
            selectedFingerprint = projection.selectedFingerprint,
            activeFacing = projection.activeFacing.name,
            switchFacingLabel = when (projection.switchTarget) {
                LensFacing.BACK -> "Rear"
                LensFacing.FRONT -> "Front"
                LensFacing.EXTERNAL, LensFacing.UNKNOWN, null -> "Switch"
            },
            switchFacingEnabled = projection.switchEnabled,
            previewVisible = sessionState is CameraSessionState.Previewing &&
                runtime.activeSelection?.activeProfileRoutingKey == sessionState.routingKey,
            recoverableError = (sessionState as? CameraSessionState.ErrorRecoverable)?.error?.detail,
        )
        return CameraAppUiState(
            camera = cameraState,
            lensSettings = lensSettingsModels(runtime, preferences),
            diagnostics = diagnosticsState(runtime, sessionState, preferences, reference),
        )
    }

    private fun selectorLenses(
        runtime: CameraUiRuntime,
        preferences: LensPreferencesState,
        includeHidden: Boolean,
    ): List<LensDescriptor> {
        val unique = LensDuplicateFilter.filterForSelector(runtime.lenses)
        return LensPreferenceOrdering.resolve(unique, preferences, includeHidden)
            .map { it.lens }
    }

    private fun lensSettingsModels(
        runtime: CameraUiRuntime,
        preferences: LensPreferencesState,
    ): List<LensSettingsUiModel> {
        val unique = LensDuplicateFilter.filterForSelector(runtime.lenses)
        val resolved = LensPreferenceOrdering.resolve(unique, preferences, includeHidden = true)
        val reference = referenceLens(unique, preferences)
        val advancedFingerprints = runtime.topology.routes
            .filter { it.isAdvancedPhotographicCandidate }
            .mapNotNullTo(mutableSetOf()) { it.lensFingerprint?.value }
        return resolved.map { preference ->
            val lens = preference.lens
            val fingerprint = lens.fingerprint?.value ?: lens.identity.routingKey
            LensSettingsUiModel(
                fingerprint = fingerprint,
                defaultLabel = defaultLensName(lens, reference),
                customLabel = preference.displayName,
                facing = lens.facing.name.lowercase().replaceFirstChar(Char::uppercase),
                visible = preference.visible,
                isOneXReference = lens.fingerprint?.value == reference?.fingerprint?.value,
                supportsOneXReference = lens.facing == LensFacing.BACK,
                advanced = fingerprint in advancedFingerprints,
            )
        }
    }

    private fun diagnosticsState(
        runtime: CameraUiRuntime,
        sessionState: CameraSessionState,
        preferences: LensPreferencesState,
        reference: LensDescriptor?,
    ): DiagnosticsUiState {
        val records = preferences.records.associateBy { it.fingerprint }
        val selector = selectorLenses(runtime, preferences, includeHidden = false)
        val projection = CameraSelectionPolicy.project(selector, runtime.activeSelection, sessionState)
        val normalVisibleFrontCount = selector.count { it.facing == LensFacing.FRONT }
        val normalVisibleRearCount = selector.count { it.facing == LensFacing.BACK }
        return DiagnosticsUiState(
            buildSummary = listOf(
                DiagnosticField("Version", "${platform.app.versionName} (${platform.app.versionCode})"),
                DiagnosticField("Git SHA", platform.app.gitSha),
                DiagnosticField("Build", platform.app.buildType),
                DiagnosticField("Android", "${platform.android.release} / API ${platform.android.sdkInt}"),
                DiagnosticField("Device", "${platform.device.manufacturer} ${platform.device.model}"),
                DiagnosticField("Vulkan", platform.graphics.vulkanVersionReadable ?: "Not reported"),
            ),
            nativeSummary = listOf(
                DiagnosticField("Library loaded", platform.app.nativeLoaded.yesNo()),
                DiagnosticField("Version", platform.app.nativeVersion ?: "Unknown"),
                DiagnosticField("Self-test", platform.app.nativeSelfTestPassed.passFail()),
            ),
            startupTraceSummary = buildList {
                add(DiagnosticField("Cache → lenses", runtime.startupTrace.cacheToLensesMs.ms()))
                add(DiagnosticField("App → camera request", runtime.startupTrace.appToCameraRequestMs.ms()))
                add(DiagnosticField("App → first frame", runtime.startupTrace.appToFirstPreviewFrameMs.ms()))
                add(DiagnosticField("Advertised reconciliation", runtime.startupTrace.advertisedScanMs.ms()))
                add(DiagnosticField("Deep AUX scan", runtime.startupTrace.deepAuxScanMs.ms()))
                runtime.startupTrace.offsetsNs.entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (milestone, offset) ->
                        add(DiagnosticField(milestone.name, (offset / 1_000_000.0).ms()))
                    }
            },
            cacheSummary = listOf(
                DiagnosticField("Cache ready", runtime.discovery.cacheReady.yesNo()),
                DiagnosticField("Cache hit", runtime.discovery.cacheHit.yesNo()),
                DiagnosticField("Miss reason", runtime.discovery.cacheMissReason?.name ?: "None"),
                DiagnosticField(
                    "Cache generation (epoch ms)",
                    runtime.discovery.cacheGeneratedAtEpochMs?.toString() ?: "None",
                ),
                DiagnosticField("Canonical lenses", runtime.topology.routes.size.toString()),
                DiagnosticField(
                    "Camera profiles",
                    runtime.topology.routes.sumOf { it.profiles.size }.toString(),
                ),
                DiagnosticField("Normal visible front", normalVisibleFrontCount.toString()),
                DiagnosticField("Normal visible rear", normalVisibleRearCount.toString()),
                DiagnosticField("Camera UI facing", projection.activeFacing.name),
                DiagnosticField("Camera UI lens count", projection.lenses.size.toString()),
                DiagnosticField(
                    "Active session routing key",
                    runtime.activeSelection?.activeProfileRoutingKey ?: "None",
                ),
                DiagnosticField(
                    "Active canonical fingerprint",
                    runtime.activeSelection?.canonicalLensFingerprint?.value ?: "None",
                ),
                DiagnosticField(
                    "Active profile facing",
                    runtime.activeSelection?.activeProfileDescriptor?.facing?.name ?: "Unknown",
                ),
                DiagnosticField(
                    "Active canonical facing",
                    runtime.activeSelection?.facing?.name ?: "Unknown",
                ),
                DiagnosticField("Initial deep scan", runtime.discovery.initialDeepScanRequired.yesNo()),
                DiagnosticField("Environment", runtime.topology.environmentFingerprint.stableKey.take(20)),
                DiagnosticField(
                    "Advertised signature",
                    runtime.topology.environmentFingerprint.advertisedTopologySignature?.take(20)
                        ?: "Not scanned",
                ),
            ),
            discoverySummary = buildList {
                listOf(
                    "Java" to runtime.discovery.java,
                    "NDK advertised" to runtime.discovery.ndk,
                    "NDK deep" to runtime.discovery.deep,
                ).forEach { (name, backend) ->
                    add(
                        DiagnosticField(
                            name,
                            "${backend.status.name}; ${backend.candidateCount} candidates; " +
                                (backend.durationMs?.let { "$it ms" } ?: "duration pending"),
                        ),
                    )
                }
                val routes = runtime.topology.routes
                add(
                    DiagnosticField(
                        "Java IDs",
                        routes.idsFromSources(
                            CameraDiscoverySource.JAVA_PUBLIC,
                            CameraDiscoverySource.JAVA_PHYSICAL,
                        ),
                    ),
                )
                add(
                    DiagnosticField(
                        "NDK IDs",
                        routes.idsFromSources(CameraDiscoverySource.NDK_ADVERTISED),
                    ),
                )
                add(
                    DiagnosticField(
                        "Deep IDs",
                        routes.idsFromSources(CameraDiscoverySource.NDK_DEEP),
                    ),
                )
                add(
                    DiagnosticField(
                        "Physical relationships",
                        runtime.topology.logicalRelationships.joinToString { relationship ->
                            "${relationship.logicalCameraId}→" +
                                relationship.physicalCameraIds.joinToString(prefix = "[", postfix = "]")
                        }.ifBlank { "None" },
                    ),
                )
                if (runtime.discovery.javaFailures.isEmpty() &&
                    runtime.discovery.nativeFailures.isEmpty()
                ) {
                    add(DiagnosticField("Discovery failures", "None"))
                } else {
                    runtime.discovery.javaFailures.forEachIndexed { index, failure ->
                        val route = listOfNotNull(
                            failure.publicCameraId,
                            failure.physicalCameraId?.let { "physical $it" },
                        ).joinToString(" / ").ifBlank { "camera service" }
                        add(
                            DiagnosticField(
                                "Failure ${index + 1}",
                                "$route: ${failure.kind.name} (${failure.detail})",
                            ),
                        )
                    }
                    runtime.discovery.nativeFailures.forEachIndexed { index, failure ->
                        add(
                            DiagnosticField(
                                "Native failure ${index + 1}",
                                "${failure.cameraId ?: "backend"}: ${failure.stage.name}/" +
                                    failure.reason.name,
                            ),
                        )
                    }
                }
                runtime.session.livePreviewFailureMemory.forEach { (route, memory) ->
                    add(
                        DiagnosticField(
                            "Retry memory",
                            "${route.take(48)}: ${memory.consecutiveFailures} × " +
                                "${memory.lastFailureKind.name}" +
                                if (memory.automaticRetrySuppressed) " (suppressed)" else "",
                        ),
                    )
                }
            },
            lenses = runtime.topology.routes.map { route ->
                val lens = route.toLensDescriptor()
                lens.toDiagnostics(
                    records[lens.fingerprint?.value]?.displayName,
                    reference,
                    route,
                )
            },
            exportEnabled = true,
            statusText = sessionState.statusText(),
        )
    }

    private fun LensDescriptor.toDiagnostics(
        customLabel: String?,
        reference: LensDescriptor?,
        topologyRoute: CameraRoute?,
    ): LensDiagnosticsUiModel {
        val fov = LensMath.fieldOfView(capabilities)
        val fingerprintValue = fingerprint?.value ?: identity.routingKey
        val preferred = topologyRoute?.profiles?.firstOrNull {
            it.profileId == topologyRoute.preferredProfileId
        } ?: topologyRoute?.profiles?.firstOrNull()
        val profileOrder = topologyRoute?.profiles.orEmpty()
            .sortedWith(
                compareByDescending<com.sahidcode404.camex.core.camera.topology.CameraProfile> {
                    CameraProfileSelector.score(it)
                }.thenBy { it.profileFingerprint },
            )
        val profileUi = profileOrder.mapIndexed { index, profile ->
            val isPreferred = profile.profileId == topologyRoute?.preferredProfileId ||
                topologyRoute?.preferredProfileId == null && index == 0
            val failure = profile.failure
            CameraProfileDiagnosticsUiModel(
                stableKey = profile.profileId,
                title = "Profile ${index + 1}${if (isPreferred) " · preferred" else ""}",
                fields = listOf(
                    DiagnosticField("Profile fingerprint", profile.profileFingerprint),
                    DiagnosticField("Rank", "${index + 1}/${profileOrder.size}; score ${CameraProfileSelector.score(profile)}"),
                    DiagnosticField("Discovered camera ID", profile.discoveredCameraId),
                    DiagnosticField("Open camera ID", profile.openCameraId),
                    DiagnosticField("Physical camera ID", profile.streamPhysicalCameraId ?: "None"),
                    DiagnosticField("Logical parent", profile.logicalParentCameraId ?: "None"),
                    DiagnosticField("Route kind", profile.routeKind.name),
                    DiagnosticField(
                        "Discovery sources",
                        profile.discoverySources.joinToString { it.name }.ifBlank { "None" },
                    ),
                    DiagnosticField("Metadata trust", profile.metadataTrust.name),
                    DiagnosticField("Session trust", profile.sessionTrust.name),
                    DiagnosticField("RAW trust", profile.rawTrust.name),
                    DiagnosticField(
                        "Last attempt (epoch ms)",
                        profile.lastAttemptEpochMs?.toString() ?: "Never",
                    ),
                    DiagnosticField("Failure", failure?.kind?.name ?: "None"),
                    DiagnosticField("Failure durability", failure?.durability?.name ?: "None"),
                    DiagnosticField(
                        "Failure detail",
                        failure?.detail?.takeIf(String::isNotBlank) ?: "None",
                    ),
                    DiagnosticField(
                        "Preview verified",
                        (profile.sessionTrust == CameraSessionTrust.SESSION_VERIFIED).yesNo(),
                    ),
                    DiagnosticField(
                        "RAW advertised",
                        profile.metadata.rawCapabilityAdvertised.name,
                    ),
                ),
            )
        }
        return LensDiagnosticsUiModel(
            stableKey = fingerprintValue,
            fingerprint = fingerprintValue,
            title = customLabel ?: defaultLensName(this, reference),
            subtitle = "Preferred profile: ${preferred?.openCameraId ?: "None"}",
            status = usability.name,
            summary = listOf(
                DiagnosticField("Optical fingerprint", fingerprintValue),
                DiagnosticField("Facing", facing.name),
                DiagnosticField("Role", topologyRoute?.role?.name ?: category.name),
                DiagnosticField("Role confidence", topologyRoute?.roleConfidence?.name ?: "Unknown"),
                DiagnosticField("Focal length", capabilities.focalLengthsMm.mmList()),
                DiagnosticField("Approx. FOV", fov?.let {
                    "%.1f° × %.1f° (diag %.1f°)".format(
                        Locale.ROOT,
                        it.horizontalDegrees,
                        it.verticalDegrees,
                        it.diagonalDegrees,
                    )
                } ?: "Unknown"),
                DiagnosticField("Sensor geometry", capabilities.sensorPhysicalSize?.let {
                    "%.2f × %.2f mm".format(Locale.ROOT, it.widthMm, it.heightMm)
                } ?: "Unknown"),
                DiagnosticField(
                    "Canonical trust",
                    topologyRoute?.trust?.let {
                        "metadata=${it.metadata.name}; session=${it.session.name}; raw=${it.raw.name}"
                    } ?: "Unknown",
                ),
                DiagnosticField("Preferred profile", preferred?.profileFingerprint ?: "None"),
                DiagnosticField("Number of profiles", profileOrder.size.toString()),
            ),
            advanced = listOf(
                DiagnosticField("Canonical lens ID", "cl3_$fingerprintValue"),
                DiagnosticField("Category", category.name),
                DiagnosticField("Pixel array", capabilities.pixelArraySize.sizeText()),
                DiagnosticField("Hardware", capabilities.hardwareLevel.name),
                DiagnosticField("RAW", "${capabilities.flags.raw.name} / ${capabilities.rawAccess.name}"),
                DiagnosticField("Max RAW", capabilities.maximumRawSize.sizeText()),
                DiagnosticField("Fingerprint mode", fingerprint?.strategy?.name ?: "Unknown"),
                DiagnosticField("Active array", capabilities.activeArray?.let {
                    "${it.left},${it.top}–${it.right},${it.bottom}"
                } ?: "Unknown"),
                DiagnosticField("Max-res array", capabilities.maximumResolutionArray?.size.sizeText()),
                DiagnosticField("Orientation", capabilities.sensorOrientationDegrees?.let { "$it°" } ?: "Unknown"),
                DiagnosticField("Apertures", capabilities.apertures.fStopList()),
                DiagnosticField("Minimum focus", capabilities.minimumFocusDistanceDiopters?.let {
                    "%.3f D".format(Locale.ROOT, it)
                } ?: "Unknown"),
                DiagnosticField("Manual sensor", capabilities.flags.manualSensor.name),
                DiagnosticField("Manual focus", capabilities.flags.manualFocus.name),
                DiagnosticField("Burst", capabilities.flags.burst.name),
                DiagnosticField("OIS", capabilities.flags.opticalStabilization.name),
                DiagnosticField("Video stabilization", capabilities.flags.videoStabilization.name),
                DiagnosticField("High speed", capabilities.flags.highSpeedVideo.name),
                DiagnosticField("Ultra-high resolution", capabilities.flags.ultraHighResolution.name),
                DiagnosticField("ISO", capabilities.isoRange?.let { "${it.min}–${it.max}" } ?: "Unknown"),
                DiagnosticField("Exposure ns", capabilities.exposureTimeRangeNs?.let {
                    "${it.min}–${it.max}"
                } ?: "Unknown"),
                DiagnosticField("AE modes", capabilities.aeModes.listText()),
                DiagnosticField("AF modes", capabilities.afModes.listText()),
                DiagnosticField("AWB modes", capabilities.awbModes.listText()),
                DiagnosticField("Preview FPS", capabilities.previewFpsRanges.orEmpty().joinToString {
                    "${it.min}–${it.max}"
                }.ifBlank { "Unknown" }),
            ),
            profiles = profileUi,
        )
    }

    private fun referenceLens(
        lenses: List<LensDescriptor>,
        preferences: LensPreferencesState,
    ): LensDescriptor? = PrimaryLensSelector.select(lenses, userReference(preferences))

    private fun userReference(preferences: LensPreferencesState): LensFingerprint? =
        preferences.oneXReferenceFingerprint?.let {
            LensFingerprint(it, com.sahidcode404.camex.core.model.FingerprintStrategy.STABLE_METADATA)
        }

    private fun displayLabel(
        lens: LensDescriptor,
        customLabel: String?,
        reference: LensDescriptor?,
    ): String = customLabel ?: defaultLensName(lens, reference)

    private fun defaultLensName(lens: LensDescriptor, reference: LensDescriptor?): String {
        if (lens.facing == LensFacing.BACK && reference != null) {
            LensMath.relativeZoomLabel(
                LensMath.fieldOfView(reference.capabilities),
                LensMath.fieldOfView(lens.capabilities),
            )?.let { return it }
        }
        return when (lens.category) {
            LensCategory.PHOTOGRAPHIC_ULTRAWIDE -> "Ultrawide"
            LensCategory.PHOTOGRAPHIC_WIDE -> "Wide"
            LensCategory.PHOTOGRAPHIC_TELEPHOTO -> "Tele"
            LensCategory.PHOTOGRAPHIC_SUPER_TELEPHOTO -> "Super tele"
            LensCategory.PHOTOGRAPHIC_MACRO -> "Macro"
            LensCategory.PHOTOGRAPHIC_MONO -> "Monochrome"
            LensCategory.PHOTOGRAPHIC_UNKNOWN -> when (lens.facing) {
                LensFacing.FRONT -> "Front"
                LensFacing.BACK -> "Rear lens"
                LensFacing.EXTERNAL -> "External"
                LensFacing.UNKNOWN -> "Lens"
            }
            LensCategory.NON_PHOTO_DEPTH -> "Depth camera"
            LensCategory.NON_PHOTO_TOF -> "ToF camera"
            LensCategory.NON_PHOTO_IR -> "IR camera"
            LensCategory.SYSTEM_ONLY -> "System camera"
            LensCategory.INACCESSIBLE -> "Inaccessible camera"
            LensCategory.BROKEN -> "Unavailable camera"
        }
    }

    private fun CameraSessionState.statusText(): String = when (this) {
        CameraSessionState.Idle -> "Starting camera foundation"
        CameraSessionState.PermissionRequired -> "Camera permission required"
        is CameraSessionState.Ready -> if (lensCount == 0) {
            "No photographic camera is available yet; open diagnostics"
        } else {
            "$lensCount photographic camera${if (lensCount == 1) "" else "s"} available"
        }
        is CameraSessionState.AwaitingSurface -> "Preparing preview surface"
        is CameraSessionState.Opening -> "Opening lens"
        is CameraSessionState.Previewing -> "Previewing ${previewSize.width}×${previewSize.height}"
        is CameraSessionState.Switching -> "Switching lenses"
        is CameraSessionState.Closing -> "Closing camera safely"
        is CameraSessionState.Paused -> "Camera paused"
        is CameraSessionState.ErrorRecoverable -> error.detail
        CameraSessionState.Closed -> "Camera closed"
    }

    private fun List<Double>?.mmList(): String = this.orEmpty()
        .joinToString { "%.2f mm".format(Locale.ROOT, it) }
        .ifBlank { "Unknown" }

    private fun List<Double>?.fStopList(): String = this.orEmpty()
        .joinToString { "ƒ/%.1f".format(Locale.ROOT, it) }
        .ifBlank { "Unknown" }

    /** Discovery-source ID summaries must include hidden/failed sibling profiles too. */
    private fun List<CameraRoute>.idsFromSources(
        vararg sources: CameraDiscoverySource,
    ): String {
        val expected = sources.toSet()
        return asSequence()
            .flatMap { it.profiles.asSequence() }
            .filter { profile -> profile.discoverySources.any(expected::contains) }
            .map { it.discoveredCameraId }
            .distinct()
            .sorted()
            .joinToString()
            .ifBlank { "None" }
    }

    private fun com.sahidcode404.camex.core.model.Size2D?.sizeText(): String =
        this?.takeIf { it.isValid }?.let { "${it.width}×${it.height}" } ?: "Unknown"

    private fun Set<String>?.listText(): String = this.orEmpty().joinToString().ifBlank { "Unknown" }
    private fun Double?.ms(): String = this?.let { "%.2f ms".format(Locale.ROOT, it) } ?: "Pending"
    private fun Boolean.yesNo(): String = if (this) "Yes" else "No"
    private fun Boolean.passFail(): String = if (this) "Passed" else "Failed"
}
