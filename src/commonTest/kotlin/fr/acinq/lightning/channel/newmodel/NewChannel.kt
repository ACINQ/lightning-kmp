package fr.acinq.lightning.channel.newmodel


sealed class Command {
    object Command1 : Command()
}

sealed class Action {
    object Action1 : Action()
}

sealed class State {
    abstract fun Context<State>.doProcess(cmd: Command): Pair<State, List<Action>>

    data class State1(val a: String) : State() {
        override fun Context<State>.doProcess(cmd: Command): Pair<State, List<Action>> {
            // we have access to both the context and the state
            //val params = this.params
            //val state = this@State1
            return State2(10) to listOf<Action.Action1>()
        }
    }

    data class State2(val b: Int) : State() {
        override fun Context<State>.doProcess(cmd: Command): Pair<State, List<Action>> {
            TODO("Not yet implemented")
        }
    }
}


data class Params(val alias: String)

data class Context<out S : State>(
    val params: Params,
    val state: S
) {
    fun process(cmd: Command): Pair<Context<State>, List<Action>> =
        state.run { doProcess(cmd) }.let { (state1, actions) -> Context(params, state1) to actions }
}

//fun test() {
//    val channel = Context(
//        params = Params(alias = "my node"),
//        state = State.State1("foobar")
//    )
//
//    val (state1, actions) = channel.process(Command.Command1)
//}


