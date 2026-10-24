# ResourceControl

Client mod that downloads and applies the resource packs a server gives you, with your permission and with an emergency off switch.

## Features
- Applies resource packs assigned to you by the server, even different ones per player
- Asks for your permission before downloading anything, separately for every server
- Shows download progress above the hotbar
- Can keep a pack in the main menu after you leave the server, if the server allows it
- Verifies every downloaded pack (size and checksum) and keeps a local cache
- Lets you block packs with shaders and switch everything off with one command

## Requirements
- Minecraft 1.21.11, Fabric Loader, Fabric API
- The mod does nothing on its own: it applies packs that a server with the `ResourceControlPlugin` plugin assigns to you. It works on any other server too, but has nothing to apply there.

## Installation
1. Install Fabric Loader for Minecraft 1.21.11.
2. Put `ResourceControl-<version>.jar` and Fabric API into the `mods` folder.
3. Join a server that uses `ResourceControlPlugin`.

## Commands
| Command | Description |
|---|---|
| `/rc off` | Ignore the server, remove all packs of the mod and reload resources |
| `/rc on` | Enable the mod again |
| `/rc status` | Show whether the mod is on, whether shader packs are blocked and how many packs are active |
| `/rc clear` | Remove packs saved for the main menu |
| `/rc shaders block` | Reject packs that contain shaders |
| `/rc shaders allow` | Allow packs that contain shaders again |

## Configuration
File: `config/resourcecontrol.json` (created on first change; edit it while the game is closed)

| Key | Type | Default | Range | Description |
|---|---|---|---|---|
| `enabled` | boolean | `true` | `true`, `false` | Master switch; `false` is the same as `/rc off` |
| `blockShaders` | boolean | `false` | `true`, `false` | Reject packs with shaders, including packs saved for the main menu |

```json
{
  "enabled": true,
  "blockShaders": false
}
```

## Controls
None.

## Privacy and safety
- The mod downloads only `.zip` resource packs, only over `https`, and only from links the server gives it.
- Before the first download, a window asks whether to allow packs up to a stated size from this server. Your answer is saved on your computer only and is never sent to the server. If the server later asks for a larger size, you are asked again. If you press Deny, nothing is downloaded until you rejoin.
- Every pack is checked against its declared size and checksum. Packs are never unpacked or run; the game only reads textures, sounds and similar resources from them.
- The server learns only whether the packs were applied or an error occurred, with a short technical message. No personal data is sent.
- Files are stored in your game folder: `resourcecontrol/cache` (downloaded packs, deleted after 14 days without use), `resourcecontrol/state.json` and `resourcecontrol/consent.json`.
- To turn everything off, use `/rc off`. To delete saved data, use `/rc clear` and remove the `resourcecontrol` folder.

## Troubleshooting
| Problem | Solution |
|---|---|
| No packs appear on the server | Run `/rc status`. If the mod is off, use `/rc on`. Check that you pressed Allow in the download window; if not, rejoin |
| I pressed Deny by mistake | Leave and rejoin the server; the window appears again |
| Shaders conflict with Sodium or Iris | Use `/rc shaders block` |
| The server's look stays after I leave | The pack is saved for the main menu: use `/rc clear` or `/rc off` |
| A pack fails with a size or checksum error | The server's file may have changed; tell the server administrator |
| Mod and server do not work together | Use mod and plugin versions with the same major version (2.x) |

## License
All rights reserved.

## Русский
Клиентский мод, который скачивает и применяет ресурспаки, выданные сервером, только с вашего разрешения и с аварийным выключателем.

### Возможности
- Применяет ресурспаки, которые сервер выдал именно вам (у каждого игрока свои)
- Спрашивает разрешение перед любой загрузкой, отдельно для каждого сервера
- Показывает ход загрузки над панелью предметов
- Может оставить пак в главном меню после выхода с сервера, если сервер это разрешил
- Проверяет каждый пак (размер и контрольная сумма), хранит локальный кэш
- Позволяет заблокировать паки с шейдерами и отключить всё одной командой

### Требования
- Minecraft 1.21.11, Fabric Loader, Fabric API
- Сам по себе мод ничего не делает: он применяет паки, которые выдаёт сервер с плагином `ResourceControlPlugin`. На других серверах он работает, но применять там нечего.

### Установка
1. Установите Fabric Loader для Minecraft 1.21.11.
2. Положите `ResourceControl-<версия>.jar` и Fabric API в папку `mods`.
3. Зайдите на сервер с плагином `ResourceControlPlugin`.

### Команды
- `/rc off` — игнорировать сервер, убрать все паки мода и перезагрузить ресурсы
- `/rc on` — включить мод снова
- `/rc status` — показать состояние мода, блокировку шейдеров и число активных паков
- `/rc clear` — удалить паки, сохранённые для главного меню
- `/rc shaders block` и `/rc shaders allow` — запретить или разрешить паки с шейдерами

### Настройки
Файл `config/resourcecontrol.json` (редактируйте при закрытой игре):
- `enabled` (`true` по умолчанию) — главный выключатель, `false` равен `/rc off`;
- `blockShaders` (`false` по умолчанию) — отклонять паки с шейдерами, включая сохранённые для главного меню.

### Приватность и безопасность
- Мод скачивает только `.zip`-ресурспаки, только по `https` и только по ссылкам от сервера.
- Перед первой загрузкой окно спрашивает, разрешить ли паки до указанного размера с этого сервера. Ответ хранится только на вашем компьютере и не отправляется серверу. Если сервер запросит больший размер, вас спросят снова. После «Отклонить» ничего не скачивается до повторного входа.
- Каждый пак проверяется по заявленному размеру и контрольной сумме. Паки не распаковываются и не запускаются.
- Серверу сообщается только, применены ли паки или произошла ошибка, с короткой технической строкой. Личные данные не отправляются.
- Файлы лежат в папке игры: `resourcecontrol/cache` (удаляются после 14 дней без использования), `resourcecontrol/state.json`, `resourcecontrol/consent.json`.
- Чтобы всё выключить, используйте `/rc off`. Чтобы удалить сохранённые данные, используйте `/rc clear` и удалите папку `resourcecontrol`.

### Решение проблем
- Паки не появились: выполните `/rc status`; если мод выключен, включите его командой `/rc on`; проверьте, что нажали «Разрешить» в окне загрузки (иначе зайдите заново).
- Случайно нажали «Отклонить»: выйдите и зайдите на сервер, окно появится снова.
- Шейдеры конфликтуют с Sodium или Iris: `/rc shaders block`.
- После выхода остался вид сервера: пак сохранён для главного меню, используйте `/rc clear` или `/rc off`.
- Ошибка размера или контрольной суммы: файл на сервере мог измениться, сообщите администратору.
