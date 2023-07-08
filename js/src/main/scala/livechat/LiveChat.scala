package livechat

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import io.laminext.syntax.validation.*
import io.laminext.websocket.circe.*
import io.laminext.fetch.circe.*

import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

object LiveChat:

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      appElement()
    )

  val host = "localhost:8080"

  final case class LoginInfo(userName: String, room: String)

  val loginVar = Var(Option.empty[LoginInfo])

  def appElement(): Element =
    div(
      cls := "container mx-auto max-w-lg flex flex-col space-y-2",
      h1(
        cls := "text-3xl self-center my-8",
        "Live Chat!"
      ),
      child <-- loginVar.signal.map:
        case None => userLogin()
        case Some(login @ LoginInfo(userName, room)) =>
          val ws = WebSocket
            .url(s"ws://$host/chatRooms/$room/users/$userName")
            .json[ChatEvent, String]
            .build(managed = true, reconnectRetries = 100)
          chatRoom(login, ws)
    )

  def userLogin(): Element =
    val userNameInput = input(
      cls := ("grow", classes.input),
      tpe := "text",
      placeholder := "enter user name",
      onMountFocus
    ).validated(V.nonBlank("User name required"))
    val roomInput = input(
      cls := ("grow", classes.input),
      tpe := "text",
      value := "room1",
      placeholder := "enter room name"
    ).validated(V.nonBlank("Chat room name required"))
    val validatedForm = userNameInput.validatedValue
      .combineWith(roomInput.validatedValue)
      // TODO laminext should have cats.Applicative on Signal[ValidatedValue[Err, Out]]
      .map:
        case (Right(name), Right(room)) => Right(LoginInfo(name, room))
        case (Left(errs), Right(_))     => Left(errs)
        case (Right(_), Left(errs))     => Left(errs)
        case (Left(errs1), Left(errs2)) => Left(errs1 ++ errs2)

    val submit = loginVar.writer.contracollect[ValidatedValue[Seq[String], LoginInfo]] { case Right(value) =>
      Some(value)
    }

    form(
      thisEvents(onSubmit.preventDefault).sample(validatedForm) --> submit,
      cls := "flex flex-col space-y-2",
      div(
        cls := "flex flex-row space-x-2 items-baseline",
        span(cls := "shrink-0", "User name: "),
        userNameInput
      ),
      div(
        cls := "flex flex-row space-x-2 items-baseline",
        span(cls := "shrink-0", "Chat room: "),
        roomInput
      ),
      button(
        "Start",
        cls := classes.button,
        disabled <-- userNameInput.validatedValue.isLeft
      )
    )
  end userLogin

  def chatRoom(login: LoginInfo, ws: WebSocket[ChatEvent, String]): Element =

    final class MessageId()
    final case class Message(id: MessageId, time: js.Date, offset: Option[Int], user: String, message: String)
    object Message:
      def apply(event: ChatEvent): Option[Message] =
        event match
          case ChatEvent.UserWrote(time, offset, user, message) =>
            Some(Message(new MessageId(), new js.Date(time.toDouble), Some(offset), user, message))
          case ChatEvent.UserJoined(user) if user != login.userName =>
            Some(Message(new MessageId(), new js.Date(), None, user, "-- joined the chat --"))
          case ChatEvent.UserLeft(user) =>
            Some(Message(new MessageId(), new js.Date(), None, user, "-- left the chat --"))
          case _ => None

    def renderMessage(message: Signal[Message]): HtmlElement =
      div(
        cls := "flex flex-row space-x-2",
        span(cls := "grow-0", child.text <-- message.map(m => m.time.toLocaleTimeString())),
        span(cls := "grow-0", child.text <-- message.map(m => s"${m.user}:")),
        span(cls := "grow", child.text <-- message.map(_.message))
      )

    val messagesVal = Var(Seq.empty[Message])
    val weReceive   = ws.received --> messagesVal.updater[ChatEvent]((messages, event) => messages ++ Message(event))

    val topMessageOffset =
      messagesVal.signal.map(_.collectFirst { case Message(_, _, Some(offset), _, _) => offset }.filter(_ != 0))

    def getHistory(from: Int, to: Int) =
      Fetch
        .get(s"http://$host/chatRooms/${login.room}/history?from=$from&to=$to")
        .decodeOkay[Seq[ChatEvent]]

    def chatMessages(): Element =

      val (historyStream, historyReceived) = EventStream.withCallback[FetchResponse[Seq[ChatEvent]]]

      div(
        cls := "w-full h-96 overflow-auto border-2 border-gray-600 rounded-lg p-4 space-y-2",
        weReceive,
        if messagesVal.now().isEmpty then getHistory(-10, -1) --> historyReceived else emptyNode,
        historyStream --> messagesVal.updater[FetchResponse[Seq[ChatEvent]]]((messages, events) =>
          events.data.flatMap(Message(_)) ++ messages
        ),
        child.maybe <-- topMessageOffset.map(
          _.map(offset =>
            button(
              "More Messages...",
              cls := ("w-full self-center", classes.buttonTernary),
              styleProp[String]("overflow-anchor") := "none",
              onClick.flatMap(_ => getHistory((offset - 10).max(0), (offset - 1).max(0))) --> historyReceived
            )
          )
        ),
        children <-- messagesVal.signal.split(_.id)((_, _, s) =>
          renderMessage(s).amend(styleProp[String]("overflow-anchor") := "none")
        ),
        // CSS trick to make the scroll stick to the bottom
        // https://css-tricks.com/books/greatest-css-tricks/pin-scrolling-to-bottom/
        div(
          styleProp[String]("overflow-anchor") := "auto",
          height := "1px"
        )
      )
    end chatMessages

    def messageInput(): Element =
      val inputMessageVar = Var("")
      val submit          = Observer.combine(ws.send, Observer[String](_ => inputMessageVar.set("")))

      form(
        thisEvents(onSubmit.preventDefault).sample(inputMessageVar.signal).filter(_.nonEmpty) --> submit,
        cls := "flex flex-row w-full space-x-2",
        input(
          cls := ("grow", classes.input),
          tpe := "text",
          placeholder := "send a message",
          onMountFocus,
          disabled <-- !ws.isConnected,
          controlled(
            value <-- inputMessageVar,
            onInput.mapToValue --> inputMessageVar
          )
        ),
        button(
          "send",
          cls := classes.button,
          disabled <-- (!ws.isConnected || inputMessageVar.signal.map(_.isEmpty()))
        )
      )

    div(
      cls := "flex flex-col space-y-2",
      ws.connect,
      chatMessages(),
      messageInput(),
      div(
        cls := "flex space-x-2",
        code("connected:"),
        code(
          child.text <-- ws.isConnected.map(_.toString)
        )
      ),
      button(
        onClick.mapTo(None) --> loginVar,
        cls := classes.buttonSecondary,
        "Leave room"
      )
    )
  end chatRoom

  object classes:
    val input =
      "shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline"
    val button          = "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded"
    val buttonSecondary = "bg-gray-700 hover:bg-gray-500 text-white font-bold py-2 px-4 rounded"
    val buttonTernary   = "bg-slate-50 hover:bg-slate-100 text-black font-bold py-2 px-4 rounded"
end LiveChat
