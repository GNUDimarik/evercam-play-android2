#ifndef EVENTLOOP_H
#define EVENTLOOP_H

#include <gst/gst.h>
#include <memory>
#include <stdexcept>

namespace evercam {

class EventLoop
{
    friend class MediaPlayer;
public:
    EventLoop() throw (std::runtime_error);
    ~EventLoop();
    void exec();
    void quit();
private:
    std::shared_ptr<GMainContext> msp_main_ctx;
    std::shared_ptr<GMainLoop> msp_main_loop;
};

} // evercam

#endif // EVENTLOOP_H
