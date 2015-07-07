#include "eventloop.h"
#include "debug.h"

using namespace evercam;

EventLoop::EventLoop() throw (std::runtime_error)
    : msp_main_ctx(g_main_context_new(), g_main_context_unref),
      msp_main_loop(g_main_loop_new(msp_main_ctx.get(), FALSE), g_main_loop_unref)
{
    if (!msp_main_ctx)
        throw std::runtime_error("Could not to create main ontext");

    if (!msp_main_loop)
        throw std::runtime_error("Could not to create main loop");

    g_main_context_push_thread_default(msp_main_ctx.get());
}

EventLoop::~EventLoop()
{
    quit();
    g_main_context_pop_thread_default(msp_main_ctx.get());
}

void EventLoop::exec()
{
    if (!g_main_loop_is_running(msp_main_loop.get()))
        g_main_loop_run(msp_main_loop.get());
}

void EventLoop::quit()
{
    if (g_main_loop_is_running(msp_main_loop.get()))
        g_main_loop_quit(msp_main_loop.get());
}
