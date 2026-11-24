package dev.dronchik.irlfog;

import dev.dronchik.irlfog.FogRegion.Kind;
import dev.dronchik.irlfog.Keyframes.Ease;
import dev.dronchik.irlfog.Keyframes.Key;
import dev.dronchik.irlfog.Keyframes.Param;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Окно «Дым · IRL Fog», рисуется внутри кадра ImGui редактора IRL Redactor (см. LightEditorPanelMixin). */
public final class FogPanel {
    private static final String[] KINDS = {"Клубы (объёмные)", "Поле (туман-объём)"};
    private static final String[] SHAPES = {"Коробка", "Сфера", "Точка", "Диск", "Кольцо", "Линия"};
    private static final String[] MOTIONS = {"Спокойный", "Естественный", "Турбулентный", "Хаотичный", "Яростный", "Магический", "Замедленный"};
    private static final String[] ATTACH = {"Мировая точка", "Сущность (игрок/моб/предмет)"};
    private static final String[] EASES = {"Линейно", "Ease In", "Ease Out", "Ease In Out", "Удержание", "Своя кривая"};
    private static final String[] PROFILES = {"Low", "Medium", "High", "Ultra", "Cinematic", "Custom"};
    private static final String[] FIELD_KINDS = {"Зона ветра", "Турбулентность", "Притяжение", "Вихрь", "Маска: скрыть", "Маска: только здесь"};
    private static final String[] PARAMS;
    static {
        PARAMS = new String[Param.values().length];
        for (int i = 0; i < PARAMS.length; i++) PARAMS[i] = Param.values()[i].label;
    }

    private static final float[] F = new float[1];
    private static final int[] I = new int[1];
    private static final ImFloat IF = new ImFloat();
    private static final ImString NAME = new ImString(64), GROUP = new ImString(32), HEX = new ImString(10), PRESET_NAME = new ImString(64),
            RENAME = new ImString(64);
    private static final ImInt COMBO = new ImInt();
    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f(), VP = new Matrix4f();
    private static final float[] VIEW_A = new float[16], PROJ_A = new float[16], MODEL_A = new float[16];
    private static FogRegion selected, clipboard;
    private static ForceField selectedField;
    private static FogRegion defaults = new FogRegion();
    private static String defaultsFor = "";
    private static boolean gizmo = true, moveGroup = false, gizmoLocal = true;
    private static int gizmoOp = Operation.TRANSLATE, keyParam = 0;
    private static String status = "";
    /** gametest: index of the tab to force open (-1 = user's choice) */
    public static int testTab = -1;
    /** frames drawn, for the gametest */
    public static int frames;

    private FogPanel() {}

    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        frames++;
        float w = ImGui.getIO().getDisplaySizeX(), h = ImGui.getIO().getDisplaySizeY();
        Camera cam = mc.gameRenderer.getMainCamera();
        boolean haveCam = cam != null && h > 0 && w > 0;
        if (haveCam) matrices(mc, cam, w, h);

        ImGui.setNextWindowPos(w - 450, 0, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(450, h * 0.95f, ImGuiCond.FirstUseEver);
        if (ImGui.begin("Дым · IRL Fog##irlfog")) panel(mc, cam);
        ImGui.end();
        if (FogStore.settings.showDebug) debugger();

        if (!FogStore.REGIONS.contains(selected)) selected = null;
        if (!FogStore.FIELDS.contains(selectedField)) selectedField = null;
        if (haveCam && FogStore.settings.showBoxes) overlay(cam.position(), w, h);
        if (haveCam && gizmo && (selected != null || selectedField != null)) gizmo(cam.position());
    }

    // ------------------------------------------------------------------------------------------------ main window

    private static void panel(Minecraft mc, Camera cam) {
        FogStore.Settings s = FogStore.settings;
        if (!s.enabled) {
            ImGui.textColored(1f, 0.35f, 0.3f, 1f, "ДЫМ ВЫКЛЮЧЕН — ничего не рисуется");
            if (ImGui.button("Включить дым")) { s.enabled = true; FogStore.dirty = true; }
        }
        if (SmokeRenderer.failed()) {
            ImGui.textColored(1f, 0.35f, 0.3f, 1f, "Ошибка рендера: " + SmokeRenderer.lastError);
            if (ImGui.button("Попробовать снова")) SmokeRenderer.retry();
        }
        if (ImGui.checkbox("Дым включён", s.enabled)) { s.enabled = !s.enabled; FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Рамки", s.showBoxes)) { s.showBoxes = !s.showBoxes; FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Отладчик", s.showDebug)) { s.showDebug = !s.showDebug; FogStore.dirty = true; }
        previewControls();
        if (FogStore.worldKey == null) ImGui.textDisabled("Мир не определён — источники не сохранятся");

        ImGui.separator();
        if (ImGui.button("+ Дым")) add(Presets.create("gray"), cam);
        ImGui.sameLine();
        if (ImGui.button("+ Костёр")) add(Presets.create("campfire_small"), cam);
        ImGui.sameLine();
        if (ImGui.button("+ Туман")) add(Presets.create("morning_fog"), cam);
        ImGui.sameLine();
        if (ImGui.button("+ Взрыв")) add(Presets.create("explosion"), cam);

        if (ImGui.beginChild("##fog_list", 0, 120, true)) {
            String lastGroup = null;
            for (int i = 0; i < FogStore.REGIONS.size(); i++) {
                FogRegion r = FogStore.REGIONS.get(i);
                if (!r.group.isEmpty() && !r.group.equals(lastGroup)) ImGui.textDisabled("[" + r.group + "]");
                lastGroup = r.group;
                String info = r.kind == Kind.FIELD ? "поле" : String.valueOf(r.alive);
                String cull = r.cullReason.isEmpty() ? "" : " · " + r.cullReason;
                String label = (r.enabled ? "● " : "○ ") + r.name + "  [" + info + cull + "]##fr" + r.id;
                if (ImGui.selectable(label, r == selected)) select(r);
            }
            if (FogStore.REGIONS.isEmpty()) ImGui.textDisabled("Источников нет — добавь кнопкой выше или из «Пресетов»");
        }
        ImGui.endChild();

        FogRegion r = selected;
        if (r != null) selectedActions(r);
        if (!status.isEmpty()) ImGui.textDisabled(status);

        if (ImGui.beginTabBar("##fogtabs")) {
            if (r != null && ImGui.beginTabItem("Источник", tabFlag(0))) { general(r, mc, cam); ImGui.endTabItem(); }
            if (r != null && ImGui.beginTabItem("Вид", tabFlag(1))) { appearance(r); ImGui.endTabItem(); }
            if (r != null && ImGui.beginTabItem("Физика", tabFlag(2))) { physics(r); ImGui.endTabItem(); }
            if (r != null && ImGui.beginTabItem("Свет", tabFlag(3))) { lighting(r); ImGui.endTabItem(); }
            if (r != null && ImGui.beginTabItem("Анимация", tabFlag(4))) { animation(r); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("Поля", tabFlag(5))) { fields(cam); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("Пресеты", tabFlag(6))) { presets(cam); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("Качество", tabFlag(7))) { performance(r); ImGui.endTabItem(); }
            ImGui.endTabBar();
        }
    }

    private static int tabFlag(int i) {
        return testTab == i ? imgui.flag.ImGuiTabItemFlags.SetSelected : 0;
    }

    /** gametest / API: select an emitter in the editor. */
    public static void selectForTest(FogRegion r) {
        select(r);
    }

    private static void previewControls() {
        ImGui.text(String.format(Locale.ROOT, "Время: %.2f с (%s)", SmokeSim.time, switch (SmokeSim.timeMode()) {
            case SCENE -> "сцена";
            case REAL -> "реальное";
            case PREVIEW -> "превью";
        }));
        ImGui.sameLine();
        if (ImGui.button(SmokeSim.paused ? "Продолжить" : "Пауза")) SmokeSim.paused = !SmokeSim.paused;
        ImGui.sameLine();
        if (ImGui.button("Перезапуск")) { FogStore.REGIONS.forEach(SmokeSim::restart); SmokeSim.requestSeek(); }
        F[0] = SmokeSim.timeScale;
        ImGui.setNextItemWidth(160);
        if (ImGui.sliderFloat("Скорость симуляции", F, 0.05f, 2f, "x%.2f")) SmokeSim.timeScale = F[0];
        tip("Замедление/ускорение для предпросмотра. В режиме «сцена» анимация ключей идёт по времени сцены.");
    }

    private static void selectedActions(FogRegion r) {
        if (ImGui.button("Удалить")) { delete(r); return; }
        ImGui.sameLine();
        if (ImGui.button("Дублировать")) { FogRegion c = r.copy(); FogStore.REGIONS.add(FogStore.REGIONS.indexOf(r) + 1, c); select(c); FogStore.dirty = true; return; }
        ImGui.sameLine();
        if (ImGui.button("Копир. параметры")) { clipboard = r.copy(); status = "Параметры скопированы"; }
        ImGui.sameLine();
        if (ImGui.button("Вставить") && clipboard != null) { Presets.copyParams(clipboard, r); FogStore.dirty = true; status = "Параметры вставлены"; }
        if (ImGui.button("Сохранить")) { FogStore.save(); status = "Сохранено"; }
        ImGui.sameLine();
        if (ImGui.button("Перезапустить источник")) SmokeSim.restart(r);
        ImGui.sameLine();
        if (ImGui.checkbox("Гизмо", gizmo)) gizmo = !gizmo;
        if (gizmo) {
            if (ImGui.radioButton("Перемещ.", gizmoOp == Operation.TRANSLATE)) gizmoOp = Operation.TRANSLATE;
            ImGui.sameLine();
            if (ImGui.radioButton("Поворот", gizmoOp == Operation.ROTATE)) gizmoOp = Operation.ROTATE;
            ImGui.sameLine();
            if (ImGui.radioButton("Масштаб", gizmoOp == Operation.SCALE)) gizmoOp = Operation.SCALE;
            ImGui.sameLine();
            if (ImGui.checkbox("Локально", gizmoLocal)) gizmoLocal = !gizmoLocal;
            ImGui.sameLine();
            if (ImGui.checkbox("Группой", moveGroup)) moveGroup = !moveGroup;
            tip("Перемещать вместе все источники той же группы");
        }
    }

    // ------------------------------------------------------------------------------------------------ tabs

    private static void general(FogRegion r, Minecraft mc, Camera cam) {
        FogRegion d = defaults(r);
        if (ImGui.inputText("Название", NAME)) { r.name = NAME.get(); FogStore.dirty = true; }
        if (ImGui.inputText("Группа", GROUP)) { r.group = GROUP.get().trim(); FogStore.dirty = true; }
        tip("Источники с одной группой можно двигать вместе (гизмо «Группой») и привязывать к ним поля");
        if (ImGui.checkbox("Включён", r.enabled)) { r.enabled = !r.enabled; FogStore.dirty = true; }
        ImGui.sameLine();
        ImGui.textDisabled("пресет: " + (r.preset.isEmpty() ? "—" : Presets.displayName(r.preset)));
        if (!r.preset.isEmpty() && ImGui.button("Сбросить всё на пресет")) { Presets.apply(r, r.preset); FogStore.dirty = true; }
        COMBO.set(r.kind.ordinal());
        if (ImGui.combo("Тип", COMBO, KINDS)) { r.kind = Kind.values()[COMBO.get()]; SmokeSim.restart(r); FogStore.dirty = true; }
        tip("Клубы — симулируемые объёмные клубы (дым, пар, взрывы). Поле — один объём тумана в коробке.");
        if (r.kind == Kind.PUFFS) {
            COMBO.set(r.shape.ordinal());
            if (ImGui.combo("Форма источника", COMBO, SHAPES)) { r.shape = FogRegion.Shape.values()[COMBO.get()]; FogStore.dirty = true; }
        }

        if (ImGui.collapsingHeader("Положение", ImGuiTreeNodeFlags.DefaultOpen)) {
            vec3("Позиция", r.center, 0.1f, "Центр источника в мире");
            vec3("Поворот", r.rotation, 0.5f, "Поворот зоны, градусы X/Y/Z");
            if (ImGui.dragFloat3("Масштаб (размер)", r.size, 0.05f, 0.05f, 4096)) FogStore.dirty = true;
            tip("Размер зоны рождения клубов / объёма поля, блоки");
            if (ImGui.button("Сюда (перед камерой)") && cam != null) moveTo(r, cam, 6);
            ImGui.sameLine();
            if (ImGui.button("Под камеру") && cam != null) moveTo(r, cam, 0);
            ImGui.sameLine();
            if (ImGui.button("На блок под прицелом") && cam != null) toBlock(mc, r, cam);
        }
        if (ImGui.collapsingHeader("Привязка", 0)) {
            COMBO.set(r.attach.ordinal());
            if (ImGui.combo("Привязка", COMBO, ATTACH)) { r.attach = FogRegion.Attach.values()[COMBO.get()]; FogStore.dirty = true; }
            if (r.attach == FogRegion.Attach.ENTITY) {
                ImGui.text("Сущность: " + (r.attachName.isEmpty() ? "не выбрана" : r.attachName));
                if (ImGui.button("Под прицелом") && cam != null) attachLook(mc, r, cam);
                ImGui.sameLine();
                if (ImGui.button("К себе") && mc.player != null) attachTo(r, mc.player);
                ImGui.sameLine();
                if (ImGui.button("Отцепить")) { r.attach = FogRegion.Attach.WORLD; r.attachUuid = r.attachName = ""; FogStore.dirty = true; }
                vec3("Смещение", r.offset, 0.02f, "Смещение от позиции сущности (ноги), блоки");
                ImGui.textDisabled("Привязка к костям моделей недоступна: у сущностей нет общего API костей.");
            }
            if (ImGui.checkbox("Клубы двигаются вместе с источником", r.followSource)) { r.followSource = !r.followSource; FogStore.dirty = true; }
            tip("Выкл. — дым оставляет след в мире (шлейф). Вкл. — аура, движется вместе с источником.");
        }
        if (r.kind == Kind.PUFFS && ImGui.collapsingHeader("Рождение", ImGuiTreeNodeFlags.DefaultOpen)) {
            r.rate = f("Скорость создания", r.rate, 0, 200, d.rate, "Клубов в секунду");
            r.intensity = f("Интенсивность", r.intensity, 0, 3, d.intensity, "Общий множитель количества и плотности");
            I[0] = r.maxParticles;
            if (ImGui.sliderInt("Максимум клубов", I, 1, 2000)) { r.maxParticles = I[0]; FogStore.dirty = true; }
            tip("Предел живых клубов у источника");
            r.life = f("Жизнь клуба", r.life, 0.3f, 120, d.life, "Секунды");
            r.sizeMin = f("Радиус от", r.sizeMin, 0.02f, 8, d.sizeMin, "Радиус клуба при рождении, блоки");
            r.sizeMax = f("Радиус до", r.sizeMax, 0.02f, 8, d.sizeMax, "Радиус клуба при рождении, блоки");
            vec3("Направление", r.dir, 0.01f, "Направление начальной скорости");
            if (ImGui.button("Направление = взгляд") && cam != null) {
                Vec3 v = Vec3.directionFromRotation(cam.xRot(), cam.yRot());
                r.dir[0] = (float) v.x; r.dir[1] = (float) v.y; r.dir[2] = (float) v.z;
                FogStore.dirty = true;
            }
            r.speed = f("Скорость", r.speed, -10, 30, d.speed, "Начальная скорость вдоль направления, бл/с");
            if (ImGui.checkbox("Рождение у низа зоны", r.fromBottom)) { r.fromBottom = !r.fromBottom; FogStore.dirty = true; }
            ImGui.sameLine();
            if (ImGui.checkbox("Не выходить за зону", r.confine)) { r.confine = !r.confine; FogStore.dirty = true; }
            if (ImGui.checkbox("Выброс (взрыв)", r.burst)) { r.burst = !r.burst; FogStore.dirty = true; }
            tip("Выпускает пачку клубов при включении источника (или перезапуске) и каждые N секунд");
            if (r.burst) {
                I[0] = r.burstCount;
                if (ImGui.sliderInt("Клубов в выбросе", I, 1, 400)) { r.burstCount = I[0]; FogStore.dirty = true; }
                r.burstInterval = f("Повтор выброса", r.burstInterval, 0, 30, d.burstInterval, "0 — один раз; иначе каждые N секунд");
            }
        }
    }

    private static void appearance(FogRegion r) {
        FogRegion d = defaults(r);
        if (ImGui.collapsingHeader("Цвет", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.colorEdit4("Цвет и прозрачность", r.color, ImGuiColorEditFlags.AlphaBar)) FogStore.dirty = true;
            tip("Цвет в начале жизни. Альфа = общая прозрачность (Opacity)");
            HEX.set(hex(r.color));
            ImGui.setNextItemWidth(100);
            if (ImGui.inputText("HEX", HEX)) parseHex(HEX.get(), r.color);
            if (ImGui.checkbox("Средний цвет", r.useMid)) { r.useMid = !r.useMid; FogStore.dirty = true; }
            if (r.useMid) {
                if (ImGui.colorEdit3("Цвет в середине", r.colorMid)) FogStore.dirty = true;
                r.midPos = f("Позиция середины", r.midPos, 0.01f, 0.99f, d.midPos, "Доля жизни, где цвет равен среднему");
            }
            if (ImGui.colorEdit3("Цвет к концу жизни", r.colorEnd)) FogStore.dirty = true;
            r.endAlpha = f("Прозрачность к концу", r.endAlpha, 0, 1, d.endAlpha, "Множитель прозрачности к концу жизни (0 = растворяется)");
            r.colorJitter = f("Разброс оттенка", r.colorJitter, 0, 0.5f, d.colorJitter, "Случайное отличие яркости клубов");
            r.hueShift = f("Смена оттенка", r.hueShift, -360, 360, d.hueShift, "Вращение оттенка, градусов в секунду жизни");
        }
        if (ImGui.collapsingHeader("Форма и шум", ImGuiTreeNodeFlags.DefaultOpen)) {
            r.density = f("Плотность", r.density, 0, 5, d.density, "Оптическая плотность дыма");
            r.softness = f("Мягкость", r.softness, 0, 1, d.softness, "Сглаживает шум: 0 — резкие клубы, 1 — ровная дымка");
            r.noiseScale = f("Масштаб шума", r.noiseScale, 0.1f, 6, d.noiseScale, "Размер деталей внутри клуба/поля");
            I[0] = r.noiseDetail;
            if (ImGui.sliderInt("Детализация шума", I, 1, 6)) { r.noiseDetail = I[0]; FogStore.dirty = true; }
            tip("Октавы fbm; ограничивается профилем качества");
            r.contrast = f("Контраст", r.contrast, 0, 3, d.contrast, "Насколько шум «выедает» форму");
            r.edgeSoftness = f("Мягкость краёв", r.edgeSoftness, 0.02f, 1, d.edgeSoftness, "Ширина плавного перехода на краях");
            r.distortion = f("Искажение формы", r.distortion, 0, 3, d.distortion, "Сила domain warp — завихрения формы");
            r.animSpeed = f("Скорость анимации", r.animSpeed, 0, 5, d.animSpeed, "Скорость изменения формы во времени");
            if (r.kind == Kind.PUFFS) {
                r.grow = f("Расширение", r.grow, 0.2f, 12, d.grow, "Во сколько раз клуб вырастает к концу жизни (Expansion)");
                r.expandCurve = f("Резкость расширения", r.expandCurve, 0, 6, d.expandCurve, "0 — равномерно; больше — быстрый рост вначале (взрыв)");
                r.fadeIn = f("Проявление", r.fadeIn, 0, 1, d.fadeIn, "Доля жизни на плавное появление");
                r.fadeOut = f("Растворение", r.fadeOut, 0, 1, d.fadeOut, "Доля жизни на плавное исчезновение");
            } else r.heightFalloff = f("Спад по высоте", r.heightFalloff, 0, 3, d.heightFalloff, "Насколько быстро туман редеет вверх (на блок)");
        }
        if (ImGui.collapsingHeader("Свечение", 0)) {
            r.emission = f("Свечение", r.emission, 0, 4, d.emission, "Собственное свечение (Emission)");
            if (ImGui.colorEdit3("Цвет свечения", r.emissionColor)) FogStore.dirty = true;
            if (ImGui.checkbox("Не темнеет в темноте (Glow)", r.glow)) { r.glow = !r.glow; FogStore.dirty = true; }
        }
    }

    private static void physics(FogRegion r) {
        FogRegion d = defaults(r);
        COMBO.set(r.motion.ordinal());
        if (ImGui.combo("Характер движения", COMBO, MOTIONS)) { r.motion = FogRegion.Motion.values()[COMBO.get()]; FogStore.dirty = true; }
        tip("Множители турбулентности, скорости анимации и вихря поверх параметров ниже");
        if (r.kind == Kind.FIELD) {
            ImGui.textDisabled("Поле не симулируется: дрейф = направление·скорость + ветер.");
            r.speed = f("Скорость дрейфа", r.speed, 0, 10, d.speed, "бл/с вдоль направления");
            vec3("Направление дрейфа", r.dir, 0.01f, "Куда сносит рисунок тумана");
            r.turbulence = f("Турбулентность", r.turbulence, 0, 4, d.turbulence, "Скорость перемешивания рисунка");
            r.windResponse = f("Реакция на ветер", r.windResponse, 0, 3, d.windResponse, "Множитель глобального ветра");
            return;
        }
        if (ImGui.collapsingHeader("Подъём и температура", ImGuiTreeNodeFlags.DefaultOpen)) {
            r.temperature = f("Температура", r.temperature, 0, 3, d.temperature, "Начальная температура клуба: тянет вверх через плавучесть");
            r.buoyancy = f("Плавучесть", r.buoyancy, -5, 8, d.buoyancy, "Ускорение вверх на единицу температуры (минус — оседает)");
            r.cooling = f("Остывание", r.cooling, 0, 5, d.cooling, "Скорость остывания, 1/с");
            r.gravity = f("Гравитация", r.gravity, -3, 5, d.gravity, "Постоянное ускорение вниз, бл/с²");
            r.rise = f("Восходящий поток", r.rise, -3, 3, d.rise, "Скорость воздуха вверх (минус — вниз), бл/с");
        }
        if (ImGui.collapsingHeader("Воздух", ImGuiTreeNodeFlags.DefaultOpen)) {
            r.drag = f("Сопротивление", r.drag, 0, 6, d.drag, "Как быстро клуб перенимает скорость воздуха");
            r.turbulence = f("Турбулентность", r.turbulence, 0, 6, d.turbulence, "Сила curl-noise завихрений, бл/с");
            r.curlScale = f("Масштаб завихрений", r.curlScale, 0.02f, 3, d.curlScale, "Частота curl noise: меньше — крупные вихри");
            r.vorticity = f("Вращение вокруг оси", r.vorticity, -10, 10, d.vorticity, "Скорость закрутки вокруг оси Y источника (Vorticity)");
            r.diffusion = f("Диффузия", r.diffusion, 0, 3, d.diffusion, "Случайное блуждание клубов");
            r.pressure = f("Давление выброса", r.pressure, -15, 20, d.pressure, "Начальная скорость от центра наружу (минус — внутрь)");
            r.dissipation = f("Рассеивание", r.dissipation, 0, 2, d.dissipation, "Потеря плотности со временем, 1/с");
            vec3("Ветер источника", r.wind, 0.01f, "Направление собственного ветра источника");
            r.windStrength = f("Сила ветра источника", r.windStrength, 0, 15, d.windStrength, "бл/с");
            r.windResponse = f("Реакция на ветер", r.windResponse, 0, 3, d.windResponse, "Множитель всех ветров для этого источника");
        }
        if (ImGui.collapsingHeader("Столкновения и взаимодействие", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.checkbox("Столкновения с блоками", r.collide)) { r.collide = !r.collide; FogStore.dirty = true; }
            tip("Клубы упираются в стены, потолок и пол (приближённо, по центру и переднему краю)");
            r.surfaceSpread = f("Растекание по поверхности", r.surfaceSpread, 0, 2, d.surfaceSpread, "Сколько вертикальной скорости уходит вдоль потолка/пола");
            if (ImGui.checkbox("Реагирует на движущиеся сущности", r.interact)) { r.interact = !r.interact; FogStore.dirty = true; }
            tip("Игроки и мобы, проходя сквозь дым, увлекают и раздвигают его");
        }
    }

    private static void lighting(FogRegion r) {
        FogRegion d = defaults(r);
        r.lightResponse = f("Реакция на свет", r.lightResponse, 0, 1, d.lightResponse, "0 — плоский цвет без освещения, 1 — полностью освещённый");
        r.scattering = f("Рассеивание света", r.scattering, 0, 3, d.scattering, "Сила рассеянного солнечного/лунного и точечного света");
        r.absorption = f("Поглощение", r.absorption, 0.05f, 4, d.absorption, "Множитель поглощения (плотнее и темнее)");
        r.anisotropy = f("Рассеяние вперёд", r.anisotropy, -0.9f, 0.9f, d.anisotropy, "Henyey-Greenstein g: >0 — ярче против света (контровой)");
        r.selfShadow = f("Самозатенение", r.selfShadow, 0, 3, d.selfShadow, "Насколько дым затеняет сам себя");
        r.ambient = f("Окружающий свет", r.ambient, 0, 3, d.ambient, "Сила света неба и блоков");
        if (ImGui.colorEdit3("Цвет освещения", r.lightTint)) FogStore.dirty = true;
        tip("Тонирует весь падающий на дым свет");
        r.emission = f("Сила свечения", r.emission, 0, 4, d.emission, "Emission Strength");
        r.fireGlow = f("Подсветка огнём снизу", r.fireGlow, 0, 3, d.fireGlow, "Тёплый свет на нижней части клубов, гаснет с остыванием");
        if (ImGui.colorEdit3("Цвет огня", r.fireColor)) FogStore.dirty = true;
        ImGui.separator();
        FogStore.Settings s = FogStore.settings;
        ImGui.textDisabled("Источники света (общие):");
        if (ImGui.checkbox("Солнце/луна", s.sunLight)) { s.sunLight = !s.sunLight; FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Свет блоков", s.blockLight)) { s.blockLight = !s.blockLight; FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Свет IRL Redactor", s.irlLights)) { s.irlLights = !s.irlLights; FogStore.dirty = true; }
        tip("Цветные точечные/прожекторные источники редактора света подсвечивают ближайший к ним дым");
    }

    private static void animation(FogRegion r) {
        FogStore.Settings s = FogStore.settings;
        ImGui.textWrapped("Время шкалы: «Сцена» — тики мира (совпадает с реплеем Flashback, перемотка пересчитывает дым), «Реальное» — часы, «Превью» — свой таймлайн.");
        if (ImGui.radioButton("Сцена", !SmokeSim.previewMode && s.timeSource == FogStore.TimeSource.SCENE)) { SmokeSim.previewMode = false; s.timeSource = FogStore.TimeSource.SCENE; SmokeSim.requestSeek(); FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.radioButton("Реальное", !SmokeSim.previewMode && s.timeSource == FogStore.TimeSource.REAL)) { SmokeSim.previewMode = false; s.timeSource = FogStore.TimeSource.REAL; SmokeSim.requestSeek(); FogStore.dirty = true; }
        ImGui.sameLine();
        if (ImGui.radioButton("Превью", SmokeSim.previewMode)) { SmokeSim.previewMode = true; SmokeSim.previewTime = (float) SmokeSim.time; }
        if (SmokeSim.previewMode) {
            if (ImGui.button(SmokeSim.previewPlaying ? "Стоп" : "Пуск")) SmokeSim.previewPlaying = !SmokeSim.previewPlaying;
            ImGui.sameLine();
            float max = Math.max(10, lastKeyTime(r) + 2);
            F[0] = SmokeSim.previewTime;
            ImGui.setNextItemWidth(-1);
            if (ImGui.sliderFloat("##scrub", F, 0, max, "%.2f с")) { SmokeSim.previewTime = F[0]; SmokeSim.requestSeek(); }
        }
        ImGui.separator();
        if (ImGui.checkbox("Зациклить", r.loopAnim)) { r.loopAnim = !r.loopAnim; FogStore.dirty = true; }
        if (r.loopAnim) {
            r.loopStart = f("Начало цикла", r.loopStart, 0, 100000, 0, "Время начала цикла, с");
            r.loopLength = f("Длина цикла", r.loopLength, 0.1f, 600, 10, "Секунды");
        }
        COMBO.set(keyParam);
        if (ImGui.combo("Параметр", COMBO, PARAMS)) keyParam = COMBO.get();
        Param p = Param.values()[keyParam];
        float now = (float) SmokeSim.time;
        if (ImGui.button("◆ Ключ сейчас")) { Keyframes.setKey(r, p, now, p.get.apply(r), Ease.EASE_IN_OUT); FogStore.dirty = true; }
        tip("Записывает текущее значение параметра в текущее время шкалы");
        ImGui.sameLine();
        if (ImGui.button("◆ Позиция XYZ")) {
            Keyframes.setKey(r, Param.POS_X, now, r.center[0], Ease.EASE_IN_OUT);
            Keyframes.setKey(r, Param.POS_Y, now, r.center[1], Ease.EASE_IN_OUT);
            Keyframes.setKey(r, Param.POS_Z, now, r.center[2], Ease.EASE_IN_OUT);
            FogStore.dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.button("◆ Цвет")) {
            Keyframes.setKey(r, Param.COLOR_R, now, r.color[0], Ease.LINEAR);
            Keyframes.setKey(r, Param.COLOR_G, now, r.color[1], Ease.LINEAR);
            Keyframes.setKey(r, Param.COLOR_B, now, r.color[2], Ease.LINEAR);
            FogStore.dirty = true;
        }
        List<Key> keys = r.tracks.get(p.name());
        if (keys != null && !keys.isEmpty()) {
            float t0 = keys.get(0).t, t1 = Math.max(t0 + 0.01f, keys.get(keys.size() - 1).t);
            float[] curve = new float[64];
            for (int i = 0; i < 64; i++) curve[i] = Keyframes.sample(keys, t0 + (t1 - t0) * i / 63f, p == Param.ENABLED);
            ImGui.plotLines("##curve", curve, 64, 0, p.label, Float.MAX_VALUE, Float.MAX_VALUE, ImGui.getContentRegionAvailX(), 60);
            for (int i = 0; i < keys.size(); i++) {
                Key k = keys.get(i);
                ImGui.pushID("key" + i);
                F[0] = k.t;
                ImGui.setNextItemWidth(90);
                if (ImGui.dragFloat("с", F, 0.01f)) { k.t = F[0]; keys.sort((a, b) -> Float.compare(a.t, b.t)); FogStore.dirty = true; }
                ImGui.sameLine();
                F[0] = k.v;
                ImGui.setNextItemWidth(90);
                if (ImGui.dragFloat("знач.", F, 0.01f)) { k.v = F[0]; FogStore.dirty = true; }
                ImGui.sameLine();
                COMBO.set(k.ease.ordinal());
                ImGui.setNextItemWidth(100);
                if (ImGui.combo("##ease", COMBO, EASES)) { k.ease = Ease.values()[COMBO.get()]; FogStore.dirty = true; }
                ImGui.sameLine();
                if (ImGui.smallButton("x")) { keys.remove(i); FogStore.dirty = true; ImGui.popID(); break; }
                if (k.ease == Ease.CUSTOM && ImGui.dragFloat4("кривая", k.bezier, 0.01f, -1, 2)) FogStore.dirty = true;
                ImGui.popID();
            }
            if (ImGui.button("Удалить дорожку")) { r.tracks.remove(p.name()); FogStore.dirty = true; }
        } else ImGui.textDisabled("Ключей нет у «" + p.label + "»");
        ImGui.separator();
        ImGui.textDisabled("Анимированные параметры:");
        for (String name : r.tracks.keySet()) {
            try {
                ImGui.bulletText(Param.valueOf(name).label + " — " + r.tracks.get(name).size() + " кл.");
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static void fields(Camera cam) {
        FogStore.Settings s = FogStore.settings;
        ImGui.textDisabled("Глобальный ветер");
        if (ImGui.dragFloat3("Направление ветра", s.wind, 0.01f, -1, 1)) FogStore.dirty = true;
        s.windStrength = f("Сила ветра", s.windStrength, 0, 20, 0.3f, "Ветер для всех источников, бл/с");
        if (ImGui.checkbox("Показывать поля", s.showFields)) { s.showFields = !s.showFields; FogStore.dirty = true; }
        ImGui.separator();
        for (int i = 0; i < FIELD_KINDS.length; i++) {
            if (i % 3 != 0) ImGui.sameLine();
            if (ImGui.button("+ " + FIELD_KINDS[i])) {
                ForceField f = ForceField.of(ForceField.Kind.values()[i]);
                if (cam != null) {
                    Vec3 p = cam.position().add(Vec3.directionFromRotation(cam.xRot(), cam.yRot()).scale(8));
                    f.center = new float[]{(float) p.x, (float) p.y, (float) p.z};
                }
                FogStore.FIELDS.add(f);
                selectField(f);
                FogStore.dirty = true;
            }
        }
        if (ImGui.beginChild("##fields", 0, 90, true)) {
            for (int i = 0; i < FogStore.FIELDS.size(); i++) {
                ForceField f = FogStore.FIELDS.get(i);
                if (ImGui.selectable((f.enabled ? "● " : "○ ") + f.name + "##ff" + i, f == selectedField)) selectField(f);
            }
            if (FogStore.FIELDS.isEmpty()) ImGui.textDisabled("Полей нет");
        }
        ImGui.endChild();
        ForceField f = selectedField;
        if (f == null) return;
        if (ImGui.button("Удалить поле")) { FogStore.FIELDS.remove(f); selectedField = null; FogStore.dirty = true; return; }
        ImGui.sameLine();
        if (ImGui.checkbox("Включено", f.enabled)) { f.enabled = !f.enabled; FogStore.dirty = true; }
        COMBO.set(f.kind.ordinal());
        if (ImGui.combo("Вид поля", COMBO, FIELD_KINDS)) { f.kind = ForceField.Kind.values()[COMBO.get()]; FogStore.dirty = true; }
        vec3("Центр поля", f.center, 0.1f, "Центр зоны");
        if (ImGui.dragFloat3("Размер поля", f.size, 0.1f, 0.2f, 4096)) FogStore.dirty = true;
        tip("Коробка; для притяжения и вихря радиус = X/2");
        if (!f.isMask()) {
            vec3("Направление/ось", f.dir, 0.01f, "Ветер: направление; вихрь: ось вращения");
            f.strength = f("Сила", f.strength, -20, 20, 2, "Ветер бл/с; притяжение — ускорение (минус = отталкивает); вихрь — скорость закрутки");
            f.extra = f("Доп. параметр", f.extra, 0, 3, 0.3f, "Вихрь: затягивание к оси; турбулентность: частота шума");
        }
        f.falloff = f("Мягкий край", f.falloff, 0, 1, 0.3f, "Доля размера, на которой действие плавно спадает");
        GROUP.set(f.group);
        if (ImGui.inputText("Только для группы", GROUP)) { f.group = GROUP.get().trim(); FogStore.dirty = true; }
        tip("Пусто — действует на все источники");
    }

    private static void presets(Camera cam) {
        FogRegion r = selected;
        ImGui.textWrapped(r == null ? "Выбери источник, чтобы применять пресеты к нему, или создай новый." : "«Применить» меняет вид и движение выбранного источника, положение и ключи остаются.");
        for (String cat : Presets.CATEGORIES) {
            if (!ImGui.treeNode(cat)) continue;
            for (Presets.Preset p : Presets.BUILTIN.values()) {
                if (!p.category().equals(cat)) continue;
                ImGui.pushID(p.id());
                if (ImGui.smallButton("+")) add(Presets.create(p.id()), cam);
                tip("Новый источник с этим пресетом перед камерой");
                ImGui.sameLine();
                if (r != null && ImGui.smallButton("Применить")) { Presets.apply(r, p.id()); SmokeSim.restart(r); FogStore.dirty = true; }
                ImGui.sameLine();
                ImGui.text(p.name());
                ImGui.popID();
            }
            ImGui.treePop();
        }
        ImGui.separator();
        ImGui.textDisabled("Мои пресеты (config/irl-fog/presets)");
        ImGui.inputText("Имя", PRESET_NAME);
        if (r != null && ImGui.button("Сохранить выбранный как пресет")) {
            try {
                Presets.saveUser(PRESET_NAME.get().isBlank() ? r.name : PRESET_NAME.get(), r);
                status = "Пресет сохранён";
            } catch (Exception e) { status = "Ошибка: " + e.getMessage(); }
        }
        ImGui.sameLine();
        if (ImGui.button("Импорт из буфера")) {
            try {
                status = "Импортирован: " + Presets.importJson(ImGui.getClipboardText(), PRESET_NAME.get());
            } catch (Exception e) { status = "Импорт не удался: " + e.getMessage(); }
        }
        for (String name : Presets.userPresets()) {
            ImGui.pushID("u" + name);
            String id = "user:" + name;
            if (ImGui.smallButton("+")) add(Presets.create(id), cam);
            ImGui.sameLine();
            if (r != null && ImGui.smallButton("Применить")) { Presets.apply(r, id); SmokeSim.restart(r); FogStore.dirty = true; }
            ImGui.sameLine();
            if (ImGui.smallButton("Копия")) run(() -> Presets.copyUser(name));
            ImGui.sameLine();
            if (ImGui.smallButton("Экспорт")) { ImGui.setClipboardText(Presets.exportJson(id)); status = "JSON пресета в буфере обмена"; }
            ImGui.sameLine();
            if (ImGui.smallButton("Имя")) run(() -> Presets.renameUser(name, PRESET_NAME.get()));
            tip("Переименовать в значение поля «Имя»");
            ImGui.sameLine();
            if (ImGui.smallButton("Удалить")) run(() -> Presets.deleteUser(name));
            ImGui.sameLine();
            ImGui.text(name);
            ImGui.popID();
        }
    }

    private static void performance(FogRegion r) {
        FogStore.Settings s = FogStore.settings;
        COMBO.set(s.profile.ordinal());
        if (ImGui.combo("Профиль качества", COMBO, PROFILES)) { s.profile = FogStore.Profile.values()[COMBO.get()]; FogStore.dirty = true; }
        tip("Cinematic — максимум для записи, адаптивность в нём отключена; включай его только для съёмки");
        if (s.profile == FogStore.Profile.CUSTOM) {
            s.customSteps = i("Шаги клуба", s.customSteps, 3, 64, "Raymarch steps внутри клуба");
            s.customFieldSteps = i("Шаги поля", s.customFieldSteps, 6, 128, "Raymarch steps через поле тумана");
            s.customShadowSteps = i("Шаги тени", s.customShadowSteps, 0, 3, "Shadow quality: выборки самозатенения");
            s.customOctaves = i("Октавы шума", s.customOctaves, 1, 6, "Детализация");
            s.customResolution = f("Разрешение", s.customResolution, 0.25f, 1, 0.75f, "Resolution scale внутренней цели дыма");
        }
        if (ImGui.checkbox("Адаптивное качество", s.adaptive)) { s.adaptive = !s.adaptive; FogStore.dirty = true; }
        tip("Следит за временем GPU на дым и по очереди снижает: шаги → разрешение → тени → частоту дальних → детали дальних");
        s.budgetMs = f("Бюджет GPU", s.budgetMs, 0.5f, 16, 3, "Целевое время дыма на кадр, мс");
        s.spawnDistance = f("Дальность", s.spawnDistance, 16, 512, 160, "Render Distance: дальше дым не симулируется и не рисуется");
        s.quality = f("Множитель количества", s.quality, 0.1f, 2, 1, "Сколько клубов рождать (x)");
        if (ImGui.checkbox("LOD по расстоянию", s.lod)) { s.lod = !s.lod; FogStore.dirty = true; }
        s.lodNear = f("LOD ближний", s.lodNear, 4, 256, 32, "До этой дистанции — полные шаги");
        s.lodFar = f("LOD дальний", s.lodFar, 8, 512, 96, "Дальше — половина шагов и редкое обновление симуляции");
        s.farUpdateEvery = i("Обновлять дальние раз в", s.farUpdateEvery, 1, 8, "Update Frequency: кадров между шагами симуляции дальних источников");
        s.simStep = f("Шаг симуляции", s.simStep, 0.01f, 0.2f, 0.05f, "Simulation Quality: меньше — плавнее и дороже, с");
        if (r != null) {
            ImGui.separator();
            ImGui.textDisabled("Выбранный источник");
            r.renderDistance = f("Дальность источника", r.renderDistance, 8, 512, defaults(r).renderDistance, "Собственный предел дальности");
            r.qualityBias = f("Качество источника", r.qualityBias, 0.25f, 3, 1, "Множитель шагов raymarch для этого источника");
        }
        SmokePerf.Quality q = SmokePerf.current();
        ImGui.textDisabled(String.format(Locale.ROOT, "Сейчас: шаги %d/%d, тени %d, октавы %d, разрешение %.0f%%, снижение %d/%d",
                q.steps(), q.fieldSteps(), q.shadow(), q.octaves(), q.resolution() * 100, SmokePerf.level, SmokePerf.MAX_LEVEL));
    }

    private static void debugger() {
        ImGui.setNextWindowSize(330, 360, ImGuiCond.FirstUseEver);
        if (ImGui.begin("Отладчик дыма##irlfogdbg")) {
            SmokePerf.Quality q = SmokePerf.current();
            ImGui.text(String.format(Locale.ROOT, "Источники: %d, активны %d, видимы %d", SmokeSim.statEmitters, SmokeSim.statActive, SmokeSim.statVisible));
            ImGui.text(String.format(Locale.ROOT, "Объёмов в кадре: %d (клубов %d, полей %d)", SmokeRenderer.statVolumes, SmokeSim.statPuffs, SmokeSim.statFields));
            ImGui.text(String.format(Locale.ROOT, "Draw calls: %d", SmokeRenderer.statDraws));
            ImGui.text(String.format(Locale.ROOT, "GPU дыма: %s", SmokePerf.gpuTimerWorks ? String.format(Locale.ROOT, "%.2f мс (последний %.2f)", SmokePerf.gpuMs, SmokePerf.gpuMsRaw) : "таймер недоступен"));
            ImGui.text(String.format(Locale.ROOT, "Симуляция: %.2f мс, подготовка: %.2f мс", SmokeSim.simMs, SmokeRenderer.prepMs));
            ImGui.text(String.format(Locale.ROOT, "Кадр: %.2f мс (%.0f fps)", SmokePerf.frameMs, 1000 / Math.max(0.01f, SmokePerf.frameMs)));
            ImGui.text(String.format(Locale.ROOT, "VRAM ~%.1f МБ, цель %dx%d", SmokeRenderer.vramBytes() / 1048576f, SmokeRenderer.targetW, SmokeRenderer.targetH));
            ImGui.text(String.format(Locale.ROOT, "Качество: %s, шаги %d/%d, тени %d, снижение %d", FogStore.settings.profile, q.steps(), q.fieldSteps(), q.shadow(), SmokePerf.level));
            ImGui.text(String.format(Locale.ROOT, "Отсечено: выкл %d, дальность %d, вне кадра %d, пусто %d", SmokeSim.cullDisabled, SmokeSim.cullDistance, SmokeSim.cullFrustum, SmokeSim.cullEmpty));
            ImGui.separator();
            ImGui.textDisabled("Сравнение A/B (одна и та же сцена до/после изменений)");
            for (int i = 0; i < 2; i++) {
                if (ImGui.button("Замер " + SmokePerf.SLOT_NAMES[i])) SmokePerf.startBench(i);
                if (i == 0) ImGui.sameLine();
            }
            int pr = SmokePerf.benchProgress();
            if (pr >= 0) ImGui.progressBar(pr / 100f);
            for (int i = 0; i < 2; i++) {
                float[] v = SmokePerf.SLOTS[i];
                if (v != null) ImGui.text(String.format(Locale.ROOT, "%s: GPU %.2f мс, сим %.2f мс, кадр %.2f мс, клубов %.0f, ур. %.0f", SmokePerf.SLOT_NAMES[i], v[0], v[1], v[2], v[3], v[5]));
            }
            if (SmokePerf.SLOTS[0] != null && SmokePerf.SLOTS[1] != null) {
                float[] a = SmokePerf.SLOTS[0], b = SmokePerf.SLOTS[1];
                ImGui.text(String.format(Locale.ROOT, "B−A: GPU %+.2f мс, кадр %+.2f мс", b[0] - a[0], b[2] - a[2]));
            }
            if (selected != null) {
                ImGui.separator();
                ImGui.text("Выбран: " + selected.name + (selected.cullReason.isEmpty() ? " — рисуется" : " — " + selected.cullReason));
            }
        }
        ImGui.end();
    }

    // ------------------------------------------------------------------------------------------------ widgets

    /** Slider + exact input + reset + tooltip, value applied at once. */
    private static float f(String label, float v, float min, float max, float def, String tip) {
        ImGui.pushID(label);
        float avail = ImGui.getContentRegionAvailX();
        F[0] = v;
        ImGui.setNextItemWidth(avail * 0.42f);
        float out = v;
        if (ImGui.sliderFloat("##s", F, min, max, "%.3g")) out = F[0];
        tip(tip);
        ImGui.sameLine();
        IF.set(out);
        ImGui.setNextItemWidth(64);
        if (ImGui.inputFloat("##i", IF, 0, 0, "%.3f")) out = Math.max(Math.min(min, max) - 1e6f, IF.get());
        tip(tip + " (точное значение, можно за пределами слайдера)");
        ImGui.sameLine();
        if (ImGui.smallButton("R")) out = def;
        tip("Сбросить на " + String.format(Locale.ROOT, "%.3g", def));
        ImGui.sameLine();
        ImGui.text(label);
        tip(tip);
        ImGui.popID();
        if (out != v) FogStore.dirty = true;
        return out;
    }

    private static int i(String label, int v, int min, int max, String tip) {
        I[0] = v;
        if (ImGui.sliderInt(label, I, min, max)) { FogStore.dirty = true; v = I[0]; }
        tip(tip);
        return v;
    }

    private static void vec3(String label, float[] v, float speed, String tip) {
        if (ImGui.dragFloat3(label, v, speed)) FogStore.dirty = true;
        tip(tip + " (двойной клик — ввод числа)");
    }

    private static void tip(String text) {
        if (ImGui.isItemHovered()) ImGui.setTooltip(text);
    }

    private static FogRegion defaults(FogRegion r) {
        if (!defaultsFor.equals(r.preset)) {
            FogRegion t = Presets.template(r.preset);
            defaults = t != null ? t : new FogRegion();
            defaultsFor = r.preset;
        }
        return defaults;
    }

    private static void run(ThrowingRunnable a) {
        try {
            a.run();
            status = "Готово";
        } catch (Exception e) {
            status = "Ошибка: " + e.getMessage();
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static String hex(float[] c) {
        return String.format("#%02X%02X%02X", Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255));
    }

    private static void parseHex(String s, float[] c) {
        s = s.trim().replace("#", "");
        if (s.length() != 6) return;
        try {
            int v = Integer.parseInt(s, 16);
            c[0] = ((v >> 16) & 255) / 255f; c[1] = ((v >> 8) & 255) / 255f; c[2] = (v & 255) / 255f;
            FogStore.dirty = true;
        } catch (NumberFormatException ignored) {}
    }

    private static float lastKeyTime(FogRegion r) {
        float m = 0;
        for (List<Key> ks : r.tracks.values()) for (Key k : ks) m = Math.max(m, k.t);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ actions

    private static void delete(FogRegion r) {
        r.removed = true;
        SmokeSim.restart(r);
        FogStore.REGIONS.remove(r);
        selected = null;
        FogStore.save();
    }

    private static void add(FogRegion r, Camera cam) {
        if (cam != null) moveTo(r, cam, r.kind == Kind.FIELD ? 0 : 6);
        if (r.kind == Kind.FIELD && cam != null) r.center[1] = (float) cam.position().y - 1 + r.size[1] / 2 - 1.5f;
        FogStore.REGIONS.add(r);
        select(r);
        FogStore.dirty = true;
    }

    private static void moveTo(FogRegion r, Camera cam, double ahead) {
        Vec3 p = cam.position().add(Vec3.directionFromRotation(cam.xRot(), cam.yRot()).scale(ahead));
        r.center[0] = (float) p.x; r.center[1] = (float) p.y; r.center[2] = (float) p.z;
        SmokeSim.requestSeek();
        FogStore.dirty = true;
    }

    private static void toBlock(Minecraft mc, FogRegion r, Camera cam) {
        Vec3 from = cam.position(), to = from.add(Vec3.directionFromRotation(cam.xRot(), cam.yRot()).scale(96));
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) { status = "Под прицелом нет блока"; return; }
        var pos = hit.getBlockPos().relative(hit.getDirection());
        r.center[0] = pos.getX() + 0.5f; r.center[1] = pos.getY() + (r.fromBottom ? r.size[1] / 2 : 0.5f); r.center[2] = pos.getZ() + 0.5f;
        SmokeSim.requestSeek();
        FogStore.dirty = true;
    }

    private static void attachLook(Minecraft mc, FogRegion r, Camera cam) {
        Vec3 c = cam.position(), dir = Vec3.directionFromRotation(cam.xRot(), cam.yRot());
        Entity best = null;
        double bestScore = Math.cos(Math.toRadians(8));
        for (Entity e : mc.level.entitiesForRendering()) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(c);
            double d = to.length();
            if (d < 0.5 || d > 64 || e == mc.getCameraEntity()) continue;
            double dot = to.scale(1 / d).dot(dir);
            if (dot > bestScore) { bestScore = dot; best = e; }
        }
        if (best == null) { status = "Под прицелом нет сущности"; return; }
        attachTo(r, best);
    }

    private static void attachTo(FogRegion r, Entity e) {
        r.attach = FogRegion.Attach.ENTITY;
        r.attachUuid = e.getStringUUID();
        r.attachName = e.getName().getString();
        r.offset = new float[]{0, e.getBbHeight() * 0.5f, 0};
        status = "Привязан к " + r.attachName;
        FogStore.dirty = true;
    }

    private static void select(FogRegion r) {
        selected = r;
        selectedField = null;
        NAME.set(r.name);
        GROUP.set(r.group);
    }

    private static void selectField(ForceField f) {
        selectedField = f;
        selected = null;
    }

    // ------------------------------------------------------------------------------------------------ viewport

    // та же камера, что строит гизмо IRL Redactor
    private static void matrices(Minecraft mc, Camera cam, float w, float h) {
        double fov = mc.options.fov().get();
        VIEW.identity().rotateX((float) Math.toRadians(cam.xRot())).rotateY((float) Math.toRadians(cam.yRot() + 180.0));
        PROJ.identity().perspective((float) Math.toRadians(fov), w / h, 0.05f, 1000f);
        VIEW.get(VIEW_A);
        PROJ.get(PROJ_A);
        PROJ.mul(VIEW, VP);
    }

    private static void overlay(Vec3 cp, float w, float h) {
        ImDrawList dl = ImGui.getBackgroundDrawList();
        Matrix3f b = new Matrix3f();
        for (FogRegion r : FogStore.REGIONS) {
            boolean sel = r == selected || (moveGroup && selected != null && !r.group.isEmpty() && r.group.equals(selected.group));
            int col = abgr(r.color[0], r.color[1], r.color[2], sel ? 1f : r.enabled ? 0.55f : 0.25f);
            box(dl, cp, r.center, r.size, r.basis(b), w, h, col, sel ? 2.5f : 1.2f);
            if (r == selected) {
                if (r.kind == Kind.PUFFS && !r.puffs.isEmpty()) {
                    float[] bb = r.bounds;
                    box(dl, cp, new float[]{(bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, (bb[2] + bb[5]) / 2},
                            new float[]{bb[3] - bb[0], bb[4] - bb[1], bb[5] - bb[2]}, new Matrix3f(), w, h, 0x40FFFFFF, 1f);
                }
                Vector3f d = b.transform(new Vector3f(r.dir[0], r.dir[1], r.dir[2]));
                if (d.length() > 1e-3f) arrow(dl, cp, r.center, d.normalize().mul(Math.max(1.5f, r.speed)), w, h, 0xFF30D0FF);
                if (r.windStrength > 0) arrow(dl, cp, r.center, new Vector3f(r.wind[0], r.wind[1], r.wind[2]).normalize().mul(1 + r.windStrength), w, h, 0xFFFFD040);
            }
        }
        FogStore.Settings s = FogStore.settings;
        if (s.windStrength > 0 && selected != null) {
            float[] up = {selected.center[0], selected.center[1] + selected.size[1] / 2 + 1, selected.center[2]};
            arrow(dl, cp, up, new Vector3f(s.wind[0], s.wind[1], s.wind[2]).normalize().mul(1 + s.windStrength), w, h, 0xFFFFFF60);
        }
        if (!s.showFields) return;
        for (ForceField f : FogStore.FIELDS) {
            int col = f.isMask() ? 0xFF4040FF : f.kind == ForceField.Kind.WIND ? 0xFF40D0FF : 0xFFFF60C0;
            if (f != selectedField) col = (col & 0x00FFFFFF) | 0x70000000;
            box(dl, cp, f.center, f.size, new Matrix3f(), w, h, col, f == selectedField ? 2.2f : 1f);
            if (!f.isMask()) arrow(dl, cp, f.center, new Vector3f(f.dir[0], f.dir[1], f.dir[2]).normalize().mul(2 + Math.abs(f.strength) * 0.5f), w, h, col | 0xFF000000);
        }
    }

    private static void box(ImDrawList dl, Vec3 cp, float[] c, float[] s, Matrix3f rot, float w, float h, int col, float th) {
        Vector4f[] v = new Vector4f[8];
        for (int i = 0; i < 8; i++) {
            Vector3f o = rot.transform(new Vector3f(((i & 1) == 0 ? -s[0] : s[0]) / 2, ((i & 2) == 0 ? -s[1] : s[1]) / 2, ((i & 4) == 0 ? -s[2] : s[2]) / 2));
            v[i] = VP.transform(new Vector4f(c[0] + o.x - (float) cp.x, c[1] + o.y - (float) cp.y, c[2] + o.z - (float) cp.z, 1));
        }
        for (int i = 0; i < 8; i++)
            for (int bit = 1; bit < 8; bit <<= 1)
                if ((i & bit) == 0) line(dl, v[i], v[i | bit], w, h, col, th);
    }

    private static void arrow(ImDrawList dl, Vec3 cp, float[] c, Vector3f d, float w, float h, int col) {
        Vector4f a = VP.transform(new Vector4f(c[0] - (float) cp.x, c[1] - (float) cp.y, c[2] - (float) cp.z, 1));
        Vector4f b = VP.transform(new Vector4f(c[0] + d.x - (float) cp.x, c[1] + d.y - (float) cp.y, c[2] + d.z - (float) cp.z, 1));
        line(dl, a, b, w, h, col, 2.5f);
        if (b.w > 0.05f) dl.addCircleFilled((b.x / b.w * 0.5f + 0.5f) * w, (0.5f - b.y / b.w * 0.5f) * h, 4, col);
    }

    private static void line(ImDrawList dl, Vector4f a, Vector4f b, float w, float h, int col, float th) {
        final float near = 0.05f;
        if (a.w < near && b.w < near) return;
        Vector4f p = a, q = b;
        if (a.w < near) p = new Vector4f(a).lerp(b, (near - a.w) / (b.w - a.w));
        if (b.w < near) q = new Vector4f(b).lerp(a, (near - b.w) / (a.w - b.w));
        dl.addLine((p.x / p.w * 0.5f + 0.5f) * w, (0.5f - p.y / p.w * 0.5f) * h,
                (q.x / q.w * 0.5f + 0.5f) * w, (0.5f - q.y / q.w * 0.5f) * h, col, th);
    }

    /** Gizmo on the emitter's own matrix (translate * Rz*Ry*Rx * scale); rotation is read back as ZYX euler. */
    private static void gizmo(Vec3 cp) {
        float[] c, size;
        Matrix3f rot = new Matrix3f();
        if (selected != null) {
            c = selected.center; size = selected.size; selected.basis(rot);
        } else {
            c = selectedField.center; size = selectedField.size;
        }
        Matrix4f m = new Matrix4f().translation(c[0] - (float) cp.x, c[1] - (float) cp.y, c[2] - (float) cp.z).mul(new Matrix4f().set(rot))
                .scale(Math.max(0.01f, size[0]), Math.max(0.01f, size[1]), Math.max(0.01f, size[2]));
        m.get(MODEL_A);
        int op = selected == null && gizmoOp == Operation.ROTATE ? Operation.TRANSLATE : gizmoOp;
        ImGuizmo.setID(0x1f06);
        ImGuizmo.setOrthographic(false);
        ImGuizmo.setDrawList(ImGui.getBackgroundDrawList());
        ImGuizmo.setRect(0, 0, ImGui.getIO().getDisplaySizeX(), ImGui.getIO().getDisplaySizeY());
        ImGuizmo.manipulate(VIEW_A, PROJ_A, op, gizmoLocal ? Mode.LOCAL : Mode.WORLD, MODEL_A);
        if (!ImGuizmo.isUsing()) return;
        Matrix4f n = new Matrix4f().set(MODEL_A);
        float nx = (float) (cp.x + n.m30()), ny = (float) (cp.y + n.m31()), nz = (float) (cp.z + n.m32());
        float dx = nx - c[0], dy = ny - c[1], dz = nz - c[2];
        Vector3f sc = n.getScale(new Vector3f());
        c[0] = nx; c[1] = ny; c[2] = nz;
        if (op == Operation.SCALE) { size[0] = sc.x; size[1] = sc.y; size[2] = sc.z; }
        if (selected != null) {
            if (op == Operation.ROTATE) {
                Matrix3f r = new Matrix3f(n.m00() / sc.x, n.m01() / sc.x, n.m02() / sc.x, n.m10() / sc.y, n.m11() / sc.y, n.m12() / sc.y,
                        n.m20() / sc.z, n.m21() / sc.z, n.m22() / sc.z);
                Vector3f e = r.getEulerAnglesZYX(new Vector3f());
                selected.rotation[0] = (float) Math.toDegrees(e.x);
                selected.rotation[1] = (float) Math.toDegrees(e.y);
                selected.rotation[2] = (float) Math.toDegrees(e.z);
            }
            if (selected.attach == FogRegion.Attach.ENTITY) { selected.offset[0] += dx; selected.offset[1] += dy; selected.offset[2] += dz; }
            if (moveGroup && !selected.group.isEmpty())
                for (FogRegion o : FogStore.REGIONS)
                    if (o != selected && o.group.equals(selected.group)) { o.center[0] += dx; o.center[1] += dy; o.center[2] += dz; }
        }
        FogStore.dirty = true;
    }

    private static int abgr(float r, float g, float b, float a) {
        return ((int) (a * 255) << 24) | ((int) (b * 255) << 16) | ((int) (g * 255) << 8) | (int) (r * 255);
    }

}
