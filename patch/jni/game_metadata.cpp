// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

// ── Symbiosis: почему этот файл лежит в патче целиком ────────────────────
//
// Апстримная версия падает и течёт при сканировании папки с играми. Три
// отдельные причины, все три воспроизводятся на обычной библиотеке:
//
//  1. РАЗЫМЕНОВАНИЕ nullptr. CacheRomMetadata() звала loader->ReadTitle()
//     без единой проверки. Loader::GetLoader() возвращает nullptr, если
//     формат не распознан (loader.cpp:320 - и на !file тоже), а
//     Core::GetGameFileFromPath() возвращает пустой VirtualFile для
//     удалённого файла или файла без доступа. Итог - SIGSEGV прямо в
//     сканировании. Достаточно одного битого .nsp в папке.
//
//  2. ГОНКА. m_rom_metadata_cache - глобальная хэш-таблица без всякой
//     синхронизации, а обращаются к ней минимум с трёх потоков сразу:
//     поток сканирования (GameHelper.getGames на Dispatchers.IO), потоки
//     загрузки обложек Coil (их несколько) и resetMetadata(), которая
//     делает clear() из-под ещё одного. Одновременная вставка в
//     unordered_dense - это порча памяти: рехэш переносит узлы, второй
//     поток в этот момент читает уже освобождённую память. Падает не
//     сразу и не всегда в одном месте, отчего и выглядит как "случайный
//     вылет при сканировании".
//
//  3. ПАМЯТЬ. Кэш держит icon (vector<u8>, у розничной игры 100-500 КБ)
//     на КАЖДУЮ игру и не чистится никогда, кроме полного clear().
//     Вдобавок GetRomMetadata() возвращала структуру ПО ЗНАЧЕНИЮ, то есть
//     копировала весь вектор иконки на каждый вызов - а getIcon зовётся
//     на каждую перерисовку списка. На 3 ГБ памяти это заметно.
//
// Правки минимальны и не меняют поведение при успехе: та же таблица, те
// же имена, тот же JNI-контракт. Меняются только пути отказа.

#include <mutex>

#include "common/android/android_common.h"
#include "common/logging.h"
#include "core/core.h"
#include "core/file_sys/fs_filesystem.h"
#include "core/file_sys/patch_manager.h"
#include "core/loader/loader.h"
#include "core/loader/nro.h"
#include "native.h"

struct RomMetadata {
    std::string title;
    u64 programId;
    std::string developer;
    std::string version;
    std::vector<u8> icon;
    bool isHomebrew;
};

ankerl::unordered_dense::map<std::string, RomMetadata> m_rom_metadata_cache;

namespace {

/// Один замок на всю таблицу.
///
/// Не rwlock: вставка тут - обычное дело (первое сканирование добавляет
/// запись на каждую игру), а чтение стоит копейки рядом с разбором ROM,
/// который делается ВНЕ замка. Держать замок во время разбора нельзя -
/// он занимает секунды и заблокировал бы весь список.
std::mutex g_metadata_mutex;

/// Сколько байт иконок разрешено держать в кэше.
///
/// Иконки уже кэширует Coil на стороне Kotlin (25% памяти приложения),
/// так что здешняя копия - вторая. Метаданные (название, версия, id)
/// весят десятки байт и остаются навсегда; выбрасываются только иконки,
/// и только когда их набралось слишком много. Повторное чтение стоит
/// одного открытия файла и происходит лишь при прокрутке к давно не
/// виденной игре.
constexpr std::size_t kMaxIconBytes = 24 * 1024 * 1024;

/// Освобождает иконки, пока их суммарный размер не уложится в лимит.
/// Вызывать только с захваченным g_metadata_mutex.
void TrimIconsLocked(const std::string& keep) {
    std::size_t total = 0;
    for (const auto& [path, entry] : m_rom_metadata_cache) {
        total += entry.icon.size();
    }
    if (total <= kMaxIconBytes) {
        return;
    }
    for (auto& [path, entry] : m_rom_metadata_cache) {
        if (total <= kMaxIconBytes) {
            break;
        }
        if (path == keep || entry.icon.empty()) {
            continue;
        }
        total -= entry.icon.size();
        // shrink_to_fit: сам clear() у vector память не отдаёт, а именно
        // она тут и была нужна.
        entry.icon.clear();
        entry.icon.shrink_to_fit();
    }
}

} // namespace

RomMetadata CacheRomMetadata(const std::string& path) {
    RomMetadata entry;
    entry.programId = 0;
    entry.isHomebrew = false;
    entry.version = "1.0.0";

    const auto file =
        Core::GetGameFileFromPath(EmulationSession::GetInstance().System().GetFilesystem(), path);
    // Пустой файл - это удалённая игра, отозванный доступ к папке или
    // сетевой носитель, который отвалился. Раньше отсюда шли прямиком в
    // loader->ReadTitle() по нулевому указателю.
    if (!file) {
        LOG_WARNING(Frontend, "[Symbiosis] не удалось открыть {}", path);
        std::scoped_lock lock{g_metadata_mutex};
        m_rom_metadata_cache[path] = entry;
        return entry;
    }

    auto loader = Loader::GetLoader(EmulationSession::GetInstance().System(), file, 0, 0);
    if (!loader) {
        LOG_WARNING(Frontend, "[Symbiosis] формат не распознан: {}", path);
        std::scoped_lock lock{g_metadata_mutex};
        m_rom_metadata_cache[path] = entry;
        return entry;
    }

    // Разбор ROM - вне замка. Он читает и расшифровывает файл, это
    // секунды; под замком встал бы весь список игр разом.
    //
    // И целиком под try.
    //
    // ПОЧЕМУ: ReadTitle/ReadIcon/ReadControlData идут в разбор
    // зашифрованного контейнера. На повреждённом или обрезанном файле
    // оттуда прилетает исключение - std::out_of_range из разбора
    // заголовка, bad_alloc на абсурдной длине поля. Это C++-исключение,
    // и пересечь границу JNI оно не может: рантайм Android просто
    // убивает процесс (std::terminate). Никакой runCatching в Kotlin
    // такое не ловит - Kotlin в этот момент ещё не выполняется.
    //
    // Именно это и означает "спотыкается, когда ищет обложки": падение
    // происходит в нативном коде, а не в том, что я чинил в прошлый раз.
    // Kotlin-часть (`!!` в GameIconFetcher) была настоящей ошибкой, но
    // не единственной, и лечила она только половину случаев.
    try {
        loader->ReadTitle(entry.title);
        loader->ReadProgramId(entry.programId);
        loader->ReadIcon(entry.icon);

        const FileSys::PatchManager pm{
            entry.programId, EmulationSession::GetInstance().System().GetFileSystemController(),
            EmulationSession::GetInstance().System().GetContentProvider()};
        const auto control = pm.GetControlMetadata();

        if (control.first != nullptr) {
            entry.developer = control.first->GetDeveloperName();
            entry.version = control.first->GetVersionString();
        } else {
            FileSys::NACP nacp;
            if (loader->ReadControlData(nacp) == Loader::ResultStatus::Success) {
                entry.developer = nacp.GetDeveloperName();
            } else {
                entry.developer = "";
            }

            entry.version = "1.0.0";
        }

        if (loader->GetFileType() == Loader::FileType::NRO) {
            auto loader_nro = reinterpret_cast<Loader::AppLoader_NRO*>(loader.get());
            entry.isHomebrew = loader_nro->IsHomebrew();
        } else {
            entry.isHomebrew = false;
        }
    } catch (const std::exception& e) {
        // Запись всё равно кладётся в кэш - уже с тем, что успело
        // прочитаться. Пустая обложка означает заглушку в списке, а не
        // повторную попытку разбора того же битого файла на каждой
        // перерисовке.
        LOG_ERROR(Frontend, "[Symbiosis] сбой разбора {}: {}", path, e.what());
        entry.icon.clear();
    } catch (...) {
        LOG_ERROR(Frontend, "[Symbiosis] неизвестный сбой разбора {}", path);
        entry.icon.clear();
    }

    {
        std::scoped_lock lock{g_metadata_mutex};
        m_rom_metadata_cache[path] = entry;
        TrimIconsLocked(path);
    }

    return entry;
}

RomMetadata GetRomMetadata(const std::string& path, bool reload = false) {
    if (reload) {
        return CacheRomMetadata(path);
    }

    {
        std::scoped_lock lock{g_metadata_mutex};
        if (auto search = m_rom_metadata_cache.find(path); search != m_rom_metadata_cache.end()) {
            return search->second;
        }
    }

    return CacheRomMetadata(path);
}

namespace {

/// Одно текстовое поле записи, без копирования иконки.
///
/// GetRomMetadata() возвращает всю структуру целиком, включая вектор на
/// сотни килобайт. Для запроса названия это чистые потери, а название
/// спрашивают на каждую строку списка.
template <typename Field>
std::string GetRomString(const std::string& path, Field field) {
    {
        std::scoped_lock lock{g_metadata_mutex};
        if (auto search = m_rom_metadata_cache.find(path); search != m_rom_metadata_cache.end()) {
            return field(search->second);
        }
    }
    const auto entry = CacheRomMetadata(path);
    return field(entry);
}

} // namespace

extern "C" {

jboolean Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getIsValid(JNIEnv* env, jobject obj,
                                                               jstring jpath) {
    // Тоже целиком под try: эту функцию сканирование зовёт на КАЖДЫЙ
    // файл в папке, и IsBootableGameContainer с ReadProgramId разбирают
    // контейнер ровно так же, как чтение обложки. Один битый файл -
    // std::terminate посреди обхода папки.
    try {
        const auto file = EmulationSession::GetInstance().System().GetFilesystem()->OpenFile(
            Common::Android::GetJString(env, jpath), FileSys::OpenMode::Read);
        if (!file) {
            return false;
        }

        auto loader = Loader::GetLoader(EmulationSession::GetInstance().System(), file);
        if (!loader) {
            return false;
        }

        const auto file_type = loader->GetFileType();
        if (file_type == Loader::FileType::Unknown || file_type == Loader::FileType::Error) {
            return false;
        }

        if ((file_type == Loader::FileType::NSP || file_type == Loader::FileType::XCI) &&
            !Loader::IsBootableGameContainer(file, file_type)) {
            return false;
        }

        u64 program_id = 0;
        Loader::ResultStatus res = loader->ReadProgramId(program_id);
        if (res != Loader::ResultStatus::Success) {
            return false;
        }
        return true;
    } catch (const std::exception& e) {
        LOG_ERROR(Frontend, "[Symbiosis] сбой проверки файла: {}", e.what());
        return false;
    } catch (...) {
        return false;
    }
}

jstring Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getTitle(JNIEnv* env, jobject obj,
                                                            jstring jpath) {
    return Common::Android::ToJString(
        env, GetRomString(Common::Android::GetJString(env, jpath),
                          [](const RomMetadata& m) { return m.title; }));
}

jstring Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getProgramId(JNIEnv* env, jobject obj,
                                                                jstring jpath) {
    return Common::Android::ToJString(
        env, GetRomString(Common::Android::GetJString(env, jpath),
                          [](const RomMetadata& m) { return std::to_string(m.programId); }));
}

jstring Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getDeveloper(JNIEnv* env, jobject obj,
                                                                jstring jpath) {
    return Common::Android::ToJString(
        env, GetRomString(Common::Android::GetJString(env, jpath),
                          [](const RomMetadata& m) { return m.developer; }));
}

jstring Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getVersion(JNIEnv* env, jobject obj,
                                                              jstring jpath, jboolean jreload) {
    if (jreload) {
        return Common::Android::ToJString(
            env, CacheRomMetadata(Common::Android::GetJString(env, jpath)).version);
    }
    return Common::Android::ToJString(
        env, GetRomString(Common::Android::GetJString(env, jpath),
                          [](const RomMetadata& m) { return m.version; }));
}

jbyteArray Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getIcon(JNIEnv* env, jobject obj,
                                                              jstring jpath) {
    const auto path = Common::Android::GetJString(env, jpath);

    std::vector<u8> icon_data;
    {
        std::scoped_lock lock{g_metadata_mutex};
        if (auto search = m_rom_metadata_cache.find(path); search != m_rom_metadata_cache.end()) {
            icon_data = search->second.icon;
        }
    }
    if (icon_data.empty()) {
        icon_data = CacheRomMetadata(path).icon;
    }

    jbyteArray icon = env->NewByteArray(static_cast<jsize>(icon_data.size()));
    // При нехватке памяти NewByteArray возвращает nullptr и взводит
    // OutOfMemoryError. Апстрим тут же передавал его в
    // SetByteArrayRegion - падение внутри JNI вместо исключения, которое
    // Kotlin мог бы поймать.
    if (icon == nullptr) {
        env->ExceptionClear();
        return env->NewByteArray(0);
    }
    if (!icon_data.empty()) {
        env->SetByteArrayRegion(icon, 0, static_cast<jsize>(icon_data.size()),
                                reinterpret_cast<jbyte*>(icon_data.data()));
    }
    return icon;
}

jboolean Java_org_yuzu_yuzu_1emu_utils_GameMetadata_getIsHomebrew(JNIEnv* env, jobject obj,
                                                                  jstring jpath) {
    const auto path = Common::Android::GetJString(env, jpath);
    {
        std::scoped_lock lock{g_metadata_mutex};
        if (auto search = m_rom_metadata_cache.find(path); search != m_rom_metadata_cache.end()) {
            return static_cast<jboolean>(search->second.isHomebrew);
        }
    }
    return static_cast<jboolean>(CacheRomMetadata(path).isHomebrew);
}

void Java_org_yuzu_yuzu_1emu_utils_GameMetadata_resetMetadata(JNIEnv* env, jobject obj) {
    std::scoped_lock lock{g_metadata_mutex};
    m_rom_metadata_cache.clear();
}

} // extern "C"
