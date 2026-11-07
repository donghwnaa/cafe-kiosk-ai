package com.example.cafe_kiosk_ai.order

interface MenuRepository { fun catalog(): MenuCatalog; fun find(menuId: String): MenuItem? }
class InMemoryMenuRepository(private val menuCatalog: MenuCatalog = MockCatalog.default) : MenuRepository {
    override fun catalog() = menuCatalog
    override fun find(menuId: String) = menuCatalog.items.find { it.id == menuId }
}

class CommandValidator(private val menus: MenuRepository) {
    fun validate(command: AiCommand): String? {
        return when (command.type) {
        CommandType.ADD_ITEM, CommandType.REMOVE_ITEM, CommandType.CHANGE_QUANTITY, CommandType.CHANGE_OPTION -> {
            val menu = command.menuId?.let(menus::find) ?: return "메뉴 정보를 확인할 수 없습니다."
            if (command.type == CommandType.ADD_ITEM && (command.quantity !in 1..99)) return "수량을 확인해 주세요."
            val temperature = command.options["temperature"]
            val size = command.options["size"]
            if (temperature != null && temperature !in menu.temperatures) return "지원하지 않는 온도입니다."
            if (size != null && size !in menu.sizes) return "지원하지 않는 크기입니다."
            null
        }
        else -> null
        }
    }
}

class CartStore {
    private val mutableLines = mutableListOf<CartLine>()
    fun lines(): List<CartLine> = mutableLines.toList()
    fun total(): Int = mutableLines.sumOf { it.menu.price * it.quantity }
    fun apply(command: AiCommand, menus: MenuRepository): String? {
        when (command.type) {
            CommandType.ADD_ITEM -> {
                val menu = menus.find(command.menuId!!)!!
                val options = OrderOptions(command.options["temperature"] ?: "ICE", command.options["size"] ?: "R")
                val found = mutableLines.indexOfFirst { it.menu.id == menu.id && it.options == options }
                if (found >= 0) mutableLines[found] = mutableLines[found].copy(quantity = mutableLines[found].quantity + command.quantity!!)
                else mutableLines += CartLine(menu, command.quantity!!, options)
            }
            CommandType.REMOVE_ITEM -> mutableLines.removeAll { it.menu.id == command.menuId }
            CommandType.CHANGE_QUANTITY -> {
                val index = mutableLines.indexOfFirst { it.menu.id == command.menuId }
                if (index < 0) return "장바구니에서 메뉴를 찾을 수 없습니다."
                if (command.quantity == 0) mutableLines.removeAt(index) else mutableLines[index] = mutableLines[index].copy(quantity = command.quantity!!)
            }
            CommandType.CHANGE_OPTION -> {
                val index = mutableLines.indexOfFirst { it.menu.id == command.menuId }
                if (index < 0) return "장바구니에서 메뉴를 찾을 수 없습니다."
                val line = mutableLines[index]
                mutableLines[index] = line.copy(options = line.options.copy(
                    temperature = command.options["temperature"] ?: line.options.temperature,
                    size = command.options["size"] ?: line.options.size,
                ))
            }
            CommandType.CANCEL_ORDER -> mutableLines.clear()
            else -> Unit
        }
        return null
    }
}
